package com.conveyal.analysis.controllers;

import com.conveyal.analysis.AnalysisServerException;
import com.conveyal.analysis.components.Components;
import com.conveyal.analysis.components.TaskScheduler;
import com.conveyal.analysis.models.Bundle;
import com.conveyal.analysis.persistence.Persistence;
import com.conveyal.analysis.util.HttpUtils;
import com.conveyal.analysis.util.JsonUtil;
import com.conveyal.file.FileStorage;
import com.conveyal.file.FileStorageKey;
import com.conveyal.file.FileUtils;
import com.conveyal.gtfs.GTFSCache;
import com.conveyal.gtfs.GTFSFeed;
import com.conveyal.gtfs.model.Stop;
import com.conveyal.osmlib.OSM;
import com.conveyal.r5.analyst.cluster.BundleManifest;
import com.conveyal.r5.streets.OSMCache;
import com.conveyal.r5.util.ExceptionUtils;
import com.mongodb.QueryBuilder;
import org.apache.commons.fileupload.FileItem;
import org.apache.commons.fileupload.disk.DiskFileItem;
import org.bson.types.ObjectId;
import org.locationtech.jts.geom.Envelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import spark.Request;
import spark.Response;
import spark.Service;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.zip.ZipFile;

import static com.conveyal.analysis.util.JsonUtil.toJson;

/**
 * This Controller provides HTTP REST endpoints for manipulating Bundles. Bundles are sets of GTFS feeds and OSM
 * extracts that are used together to build R5 TransportNetworks, and so seen together when working on Modifications in
 * a Project. The process of uploading and processing the GTFS feeds that make up Bundles is currently embedded in the
 * Bundle handling code. It's impossible to manipulate individual feeds outside a bundle. We hope to change that soon.
 */
public class BundleController implements HttpController {

    private static final Logger LOG = LoggerFactory.getLogger(BundleController.class);

    // COMPONENT DEPENDENCIES
    // This particular controller has a lot of dependencies, should something be refactored?

    private final FileStorage fileStorage;
    private final GTFSCache gtfsCache;
    private final OSMCache osmCache;
    private final TaskScheduler taskScheduler;
    private final String bundleBucket;

    public BundleController (Components components) {
        this.fileStorage = components.fileStorage;
        this.gtfsCache = components.gtfsCache;
        this.osmCache = components.osmCache;
        this.taskScheduler = components.taskScheduler;
        this.bundleBucket = components.config.bundleBucket();
    }

    // INTERFACE METHOD

    @Override
    public void registerEndpoints (Service sparkService) {
        sparkService.path("/api/bundle", () -> {
            sparkService.get("", this::getBundles, toJson);
            sparkService.get("/:_id", this::getBundle, toJson);
            sparkService.post("", this::create, toJson);
            sparkService.put("/:_id", this::update, toJson);
            sparkService.delete("/:_id", this::deleteBundle, toJson);
        });
    }

    public interface Config {
        String bundleBucket ();
    }

    // HTTP REQUEST HANDLERS

    /**
     * Create a "Bundle" of GTFS feeds that will be used together from a set of uploaded GTFS files.
     * Given a request containing an HTML form-based file upload (a multipart/form-data POST request), interpret each
     * of the uploaded items as a GTFS feed, processing the uploaded feeds into MapDB form. This provides at least
     * some form of basic validation that the feed can be loaded at all, then creates the Bundle object in MongoDB to
     * represent the set of feeds, as well as a JSON "manifest" which is stored on S3 alongside the feeds so that
     * workers know which feeds are grouped together as a Bundle. The Bundle object is returned so it can be
     * communicated back to the client over HTTP. The processing of the feeds is done asynchronously, so the status
     * field of the Bundle will at first indicate that it is not complete. By polling these API endpoints a client
     * can see when the GTFS processing has finished, or whether any errors occurred.
     * TODO we may want to allow workers to connect to Mongo or the backend to avoid storing metadata in so many places.
     * Or simply not have "bundles" at all, and just supply a list of OSM and GTFS unique IDs to the workers.
     */
    private Bundle create (Request req, Response res) {
        // create the bundle
        final Map<String, List<FileItem>> files = HttpUtils.getRequestFiles(req.raw());
        final Bundle bundle = new Bundle();
        try {
            bundle.name = files.get("bundleName").get(0).getString("UTF-8");
            bundle.regionId = files.get("regionId").get(0).getString("UTF-8");

            if (files.get("osmId") != null) {
                bundle.osmId = files.get("osmId").get(0).getString("UTF-8");
                Bundle bundleWithOsm = Persistence.bundles.find(QueryBuilder.start("osmId").is(bundle.osmId).get()).next();
                if (bundleWithOsm == null) {
                    throw AnalysisServerException.badRequest("Selected OSM does not exist.");
                }
            }

            if (files.get("feedGroupId") != null) {
                bundle.feedGroupId = files.get("feedGroupId").get(0).getString("UTF-8");
                Bundle bundleWithFeed = Persistence.bundles.find(QueryBuilder.start("feedGroupId").is(bundle.feedGroupId).get()).next();
                if (bundleWithFeed == null) {
                    throw AnalysisServerException.badRequest("Selected GTFS does not exist.");
                }
                bundle.north = bundleWithFeed.north;
                bundle.south = bundleWithFeed.south;
                bundle.east = bundleWithFeed.east;
                bundle.west = bundleWithFeed.west;
                bundle.serviceEnd = bundleWithFeed.serviceEnd;
                bundle.serviceStart = bundleWithFeed.serviceStart;
                bundle.feeds = bundleWithFeed.feeds;
                bundle.feedsComplete = bundleWithFeed.feedsComplete;
                bundle.totalFeeds = bundleWithFeed.totalFeeds;
            }
        } catch (Exception e) {
            throw AnalysisServerException.badRequest(ExceptionUtils.asString(e));
        }

        // Set `createdBy` and `accessGroup`
        bundle.accessGroup = req.attribute("accessGroup");
        bundle.createdBy = req.attribute("email");

        Persistence.bundles.create(bundle);

        // Process OSM first, then each feed sequentially. Asynchronous so we can respond to the HTTP API call.
        taskScheduler.enqueueHeavyTask(() -> {
            try {
                if (bundle.osmId == null) {
                    // Process uploaded OSM.
                    bundle.status = Bundle.Status.PROCESSING_OSM;
                    bundle.osmId = new ObjectId().toString();
                    Persistence.bundles.modifiyWithoutUpdatingLock(bundle);

                    DiskFileItem fi = (DiskFileItem) files.get("osm").get(0);
                    OSM osm = new OSM(null);
                    osm.intersectionDetection = true;
                    osm.readPbf(fi.getInputStream());

                    fileStorage.moveIntoStorage(osmCache.getKey(bundle.osmId), fi.getStoreLocation());
                }

                if (bundle.feedGroupId == null) {
                    // Process uploaded GTFS files
                    bundle.status = Bundle.Status.PROCESSING_GTFS;
                    bundle.feedGroupId = new ObjectId().toString();
                    Persistence.bundles.modifiyWithoutUpdatingLock(bundle);

                    Envelope bundleBounds = new Envelope();
                    bundle.serviceStart = LocalDate.MAX;
                    bundle.serviceEnd = LocalDate.MIN;
                    bundle.feeds = new ArrayList<>();
                    bundle.totalFeeds = files.get("feedGroup").size();

                    for (FileItem fileItem : files.get("feedGroup")) {
                        File feedFile = ((DiskFileItem) fileItem).getStoreLocation();
                        ZipFile zipFile = new ZipFile(feedFile);
                        File tempDbFile = FileUtils.createScratchFile("db");
                        File tempDbpFile = new File(tempDbFile.getAbsolutePath() + ".p");

                        GTFSFeed feed = new GTFSFeed(tempDbFile);
                        feed.loadFromFile(zipFile, new ObjectId().toString());
                        feed.findPatterns();

                        // Populate the metadata while the feed is open
                        Bundle.FeedSummary feedSummary = new Bundle.FeedSummary(feed, bundle.feedGroupId);
                        bundle.feeds.add(feedSummary);

                        for (Stop s : feed.stops.values()) {
                            bundleBounds.expandToInclude(s.stop_lon, s.stop_lat);
                        }

                        if (bundle.serviceStart.isAfter(feedSummary.serviceStart)) {
                            bundle.serviceStart = feedSummary.serviceStart;
                        }

                        if (bundle.serviceEnd.isBefore(feedSummary.serviceEnd)) {
                            bundle.serviceEnd = feedSummary.serviceEnd;
                        }

                        // Flush db files to disk
                        feed.close();

                        // Ensure all files have been stored.
                        fileStorage.moveIntoStorage(gtfsCache.getFileKey(feedSummary.bundleScopedFeedId, "db"), tempDbFile);
                        fileStorage.moveIntoStorage(gtfsCache.getFileKey(feedSummary.bundleScopedFeedId, "db.p"), tempDbpFile);
                        fileStorage.moveIntoStorage(gtfsCache.getFileKey(feedSummary.bundleScopedFeedId, "zip"), feedFile);

                        // Increment feeds complete for the progress handler
                        bundle.feedsComplete += 1;

                        // Done in a loop the nonce and updatedAt would be changed repeatedly
                        Persistence.bundles.modifiyWithoutUpdatingLock(bundle);
                    }

                    // TODO Handle crossing the antimeridian
                    bundle.north = bundleBounds.getMaxY();
                    bundle.south = bundleBounds.getMinY();
                    bundle.east = bundleBounds.getMaxX();
                    bundle.west = bundleBounds.getMinX();
                }

                writeManifestToCache(bundle);
                bundle.status = Bundle.Status.DONE;
            } catch (Exception e) {
                // This catches any error while processing a feed with the GTFS Api and needs to be more
                // robust in bubbling up the specific errors to the UI. Really, we need to separate out the
                // idea of bundles, track uploads of single feeds at a time, and allow the creation of a
                // "bundle" at a later point. This updated error handling is a stopgap until we improve that
                // flow.
                LOG.error("Error creating bundle", e);
                bundle.status = Bundle.Status.ERROR;
                bundle.statusText = ExceptionUtils.asString(e);
            } finally {
                Persistence.bundles.modifiyWithoutUpdatingLock(bundle);
            }
        });

        return bundle;
    }

    private void writeManifestToCache (Bundle bundle) throws IOException {
        BundleManifest manifest = new BundleManifest();
        manifest.osmId = bundle.osmId;
        manifest.gtfsIds = bundle.feeds.stream().map(f -> f.bundleScopedFeedId).collect(Collectors.toList());

        String manifestFileName = bundle._id + ".json";
        File manifestFile = FileUtils.createScratchFile("json");
        JsonUtil.objectMapper.writeValue(manifestFile, manifest);

        FileStorageKey key = new FileStorageKey(bundleBucket, manifestFileName);
        fileStorage.moveIntoStorage(key, manifestFile);
    }

    private Bundle deleteBundle (Request req, Response res) throws IOException {
        Bundle bundle = Persistence.bundles.removeIfPermitted(req.params("_id"), req.attribute("accessGroup"));
        FileStorageKey key = new FileStorageKey(bundleBucket, bundle._id + ".zip");
        fileStorage.delete(key);

        return bundle;
    }

    private Bundle update (Request req, Response res) throws IOException {
        return Persistence.bundles.updateFromJSONRequest(req);
    }

    private Bundle getBundle (Request req, Response res) {
        Bundle bundle = Persistence.bundles.findByIdFromRequestIfPermitted(req);

        // Progressively update older bundles with service start and end dates on retrieval
        try {
            setBundleServiceDates(bundle, gtfsCache);
        } catch (Exception e) {
            throw AnalysisServerException.unknown(e);
        }

        return bundle;
    }

    private Collection<Bundle> getBundles (Request req, Response res) {
        return Persistence.bundles.findPermittedForQuery(req);
    }

    // UTILITY METHODS

    /**
     * Bundles created before 2018-10-04 do not have service start and end dates. This method sets the service start
     * and end dates for pre-existing bundles that do not have them set already. A database migration wasn't done
     * due to the need to load feeds which is a heavy operation. Duplicate functionality exists in the
     * Bundle.FeedSummary constructor, so these dates will be automatically set for all new Bundles.
     * TODO move this somewhere closer to the root of the package hierarchy to avoid cyclic dependencies
     */
    public static Bundle setBundleServiceDates (Bundle bundle, GTFSCache gtfsCache) {
        if (bundle.status != Bundle.Status.DONE || (bundle.serviceStart != null && bundle.serviceEnd != null)) {
            return bundle;
        }

        bundle.serviceStart = LocalDate.MAX;
        bundle.serviceEnd = LocalDate.MIN;

        for (Bundle.FeedSummary summary : bundle.feeds) {
            // Compute the feed start and end dates
            if (summary.serviceStart == null || summary.serviceEnd == null) {
                GTFSFeed feed = gtfsCache.get(Bundle.bundleScopeFeedId(summary.feedId, bundle.feedGroupId));
                summary.setServiceDates(feed);
            }
            if (summary.serviceStart.isBefore(bundle.serviceStart)) {
                bundle.serviceStart = summary.serviceStart;
            }
            if (summary.serviceEnd.isAfter(bundle.serviceEnd)) {
                bundle.serviceEnd = summary.serviceEnd;
            }
        }

        // Automated change that could occur on a `get`, so don't update the nonce
        return Persistence.bundles.modifiyWithoutUpdatingLock(bundle);
    }

}
