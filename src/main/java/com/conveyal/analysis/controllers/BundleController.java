package com.conveyal.analysis.controllers;

import com.conveyal.analysis.AnalysisServerException;
import com.conveyal.analysis.UserPermissions;
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
import com.conveyal.r5.analyst.progress.Task;
import com.conveyal.r5.streets.OSMCache;
import com.conveyal.r5.util.ExceptionUtils;
import com.mongodb.QueryBuilder;
import org.apache.commons.fileupload.FileItem;
import org.apache.commons.fileupload.disk.DiskFileItem;
import org.bson.types.ObjectId;
import org.locationtech.jts.geom.Envelope;
import org.mongojack.DBCursor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import spark.Request;
import spark.Response;
import spark.Service;

import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.zip.ZipFile;

import static com.conveyal.analysis.components.HttpApi.USER_PERMISSIONS_ATTRIBUTE;
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
    private Task create (Request req, Response res) {
        UserPermissions user = req.attribute(USER_PERMISSIONS_ATTRIBUTE);
        // create the bundle
        final Map<String, List<FileItem>> files = HttpUtils.getRequestFiles(req.raw());
        final Bundle bundle = new Bundle();
        try {
            bundle.name = files.get("bundleName").get(0).getString("UTF-8");
            bundle.regionId = files.get("regionId").get(0).getString("UTF-8");

            if (files.get("osmId") != null) {
                bundle.osmId = files.get("osmId").get(0).getString("UTF-8");
                DBCursor<Bundle> cursor = Persistence.bundles.find(QueryBuilder.start("osmId").is(bundle.osmId).get());
                if (!cursor.hasNext()) {
                    throw AnalysisServerException.badRequest("Selected OSM does not exist.");
                }
            }

            if (files.get("feedGroupId") != null) {
                bundle.feedGroupId = files.get("feedGroupId").get(0).getString("UTF-8");
                DBCursor<Bundle> cursor = Persistence.bundles.find(QueryBuilder.start("feedGroupId").is(bundle.feedGroupId).get());
                if (!cursor.hasNext()) {
                    throw AnalysisServerException.badRequest("Selected GTFS does not exist.");
                }
                Bundle bundleWithFeed = cursor.next();
                bundle.north = bundleWithFeed.north;
                bundle.south = bundleWithFeed.south;
                bundle.east = bundleWithFeed.east;
                bundle.west = bundleWithFeed.west;
                bundle.serviceEnd = bundleWithFeed.serviceEnd;
                bundle.serviceStart = bundleWithFeed.serviceStart;
                bundle.feeds = bundleWithFeed.feeds;
            } else {
                bundle.serviceStart = LocalDate.MAX;
                bundle.serviceEnd = LocalDate.MIN;
                bundle.feeds = new ArrayList<>();
            }
        } catch (UnsupportedEncodingException e) {
            throw AnalysisServerException.badRequest(ExceptionUtils.asString(e));
        }

        // Set `createdBy` and `accessGroup`
        bundle.accessGroup = user.accessGroup;
        bundle.createdBy = user.email;

        // Process OSM first, then each feed sequentially. Asynchronous so we can respond to the HTTP API call.
        final Task task = new Task()
            .withTag("title", "Process OSM and GTFS for " + bundle.name)
            .withTag("regionId", bundle.regionId)
            .withTotalWorkUnits(
                1 + // Initial setup
                ((bundle.osmId == null) ? 1: 0) +
                ((bundle.feedGroupId == null) ? files.get("feedGroup").size() : 0) +
                1 + // MongoDB creation
                1  // Write manifest to cache
            )
            .withLogEntry("Bundle creation started");

        task.withAction(p -> {
            task.increment();

            if (bundle.osmId == null) {
                bundle.osmId = new ObjectId().toString();

                DiskFileItem fi = (DiskFileItem) files.get("osm").get(0);
                task.logEntry("Processing " + fi.getName());
                OSM osm = new OSM(null);
                osm.intersectionDetection = true;
                osm.readPbf(fi.getInputStream());
                task.logEntry("OSM processing complete");

                fileStorage.moveIntoStorage(osmCache.getKey(bundle.osmId), fi.getStoreLocation());
                task.increment();
            }

            if (bundle.feedGroupId == null) {
                bundle.feedGroupId = new ObjectId().toString();
                Envelope bundleBounds = new Envelope();

                for (FileItem fileItem : files.get("feedGroup")) {
                    String fileName = fileItem.getName();
                    task.logEntry("Processing GTFS feed " + fileName);
                    File feedFile = ((DiskFileItem) fileItem).getStoreLocation();
                    ZipFile zipFile = new ZipFile(feedFile);
                    File tempDbFile = FileUtils.createScratchFile("db");
                    File tempDbpFile = new File(tempDbFile.getAbsolutePath() + ".p");

                    GTFSFeed feed = new GTFSFeed(tempDbFile);
                    feed.loadFromFile(zipFile, new ObjectId().toString());
                    task.logEntry("Loaded " + fileName + ", finding patterns");
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
                    task.logEntry("Storing processed files");
                    fileStorage.moveIntoStorage(gtfsCache.getFileKey(feedSummary.bundleScopedFeedId, "db"), tempDbFile);
                    fileStorage.moveIntoStorage(gtfsCache.getFileKey(feedSummary.bundleScopedFeedId, "db.p"), tempDbpFile);
                    fileStorage.moveIntoStorage(gtfsCache.getFileKey(feedSummary.bundleScopedFeedId, "zip"), feedFile);

                    // Indicate to the UI that a feed has been uploaded
                    task.increment();
                    task.logEntry("Finished processing " + feedSummary.name + " feed");
                }

                // TODO Handle crossing the anti-meridian
                bundle.north = bundleBounds.getMaxY();
                bundle.south = bundleBounds.getMinY();
                bundle.east = bundleBounds.getMaxX();
                bundle.west = bundleBounds.getMinX();
            }

            task.logEntry("Saving bundle");
            Persistence.bundles.create(bundle);
            task.withTag("bundleId", bundle._id); // TODO update differently?
            task.increment();

            task.logEntry("Writing bundle manifest");
            writeManifestToCache(bundle); // Requires `bundle._id` to exist.
            task.increment();

            task.logEntry("Bundle creation complete");
        });

        taskScheduler.enqueueTaskForUser(task, user);

        return task;
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
     * TODO remove this method, 2018 was a long time ago.
     */
    public static Bundle setBundleServiceDates (Bundle bundle, GTFSCache gtfsCache) {
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
