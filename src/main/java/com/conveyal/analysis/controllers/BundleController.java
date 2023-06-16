package com.conveyal.analysis.controllers;

import com.conveyal.analysis.AnalysisServerException;
import com.conveyal.analysis.UserPermissions;
import com.conveyal.analysis.components.BackendComponents;
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
import com.conveyal.gtfs.error.GTFSError;
import com.conveyal.gtfs.error.GeneralError;
import com.conveyal.gtfs.model.Stop;
import com.conveyal.gtfs.validator.PostLoadValidator;
import com.conveyal.osmlib.Node;
import com.conveyal.osmlib.OSM;
import com.conveyal.r5.analyst.progress.ProgressInputStream;
import com.conveyal.r5.analyst.cluster.TransportNetworkConfig;
import com.conveyal.r5.analyst.progress.Task;
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
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.zip.ZipFile;

import static com.conveyal.analysis.util.JsonUtil.toJson;
import static com.conveyal.file.FileCategory.BUNDLES;
import static com.conveyal.r5.analyst.progress.WorkProductType.BUNDLE;
import static com.conveyal.r5.common.GeometryUtils.checkWgsEnvelopeSize;

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
    private final TaskScheduler taskScheduler;

    public BundleController (BackendComponents components) {
        this.fileStorage = components.fileStorage;
        this.gtfsCache = components.gtfsCache;
        this.taskScheduler = components.taskScheduler;
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

    // HTTP REQUEST HANDLERS

    /**
     * Create a "Bundle" of GTFS feeds that will be used together from a set of uploaded GTFS files.
     * Given a request containing an HTML form-based file upload (a multipart/form-data POST request), interpret each
     * of the uploaded items as a GTFS feed, processing the uploaded feeds into MapDB form. This provides at least
     * some form of basic validation that the feed can be loaded at all, then creates the Bundle object in MongoDB to
     * represent the set of feeds, as well as a JSON TransportNetworkConfig which is stored on S3 alongside the feeds so
     * that workers know which feeds are grouped together as a Bundle. The Bundle object is returned so it can be
     * communicated back to the client over HTTP. The processing of the feeds is done asynchronously, so the status
     * field of the Bundle will at first indicate that it is not complete. By polling these API endpoints a client
     * can see when the GTFS processing has finished, or whether any errors occurred.
     * TODO we may want to allow workers to connect to Mongo or the backend to avoid storing metadata in so many places.
     * Or simply not have "bundles" at all, and just supply a list of OSM and GTFS unique IDs to the workers.
     */
    private Bundle create (Request req, Response res) {
        // Do some initial synchronous work setting up the bundle to fail fast if the request is bad.
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
            UserPermissions userPermissions = UserPermissions.from(req);
            bundle.accessGroup = userPermissions.accessGroup;
            bundle.createdBy = userPermissions.email;
        } catch (Exception e) {
            throw AnalysisServerException.badRequest(ExceptionUtils.stackTraceString(e));
        }
        // ID and create/update times are assigned here when we push into Mongo.
        // FIXME Ideally we'd only set and retain the ID without inserting in Mongo,
        //  but existing create() method with side effects would overwrite the ID.
        Persistence.bundles.create(bundle);

        // Submit all slower work for asynchronous processing on the backend, then immediately return the partially
        // constructed bundle from the HTTP handler. Process OSM first, then each GTFS feed sequentially.
        final UserPermissions userPermissions = UserPermissions.from(req);
        taskScheduler.enqueue(Task.create("Processing bundle " + bundle.name)
            .forUser(userPermissions)
            .setHeavy(true)
            .withWorkProduct(BUNDLE, bundle._id, bundle.regionId)
            .withAction(progressListener -> {
              try {
                if (bundle.osmId == null) {
                    // Process uploaded OSM.
                    bundle.osmId = new ObjectId().toString();
                    DiskFileItem fi = (DiskFileItem) files.get("osm").get(0);
                    // Here we perform minimal validation by loading the OSM, but don't retain the resulting MapDB.
                    OSM osm = new OSM(null);
                    osm.intersectionDetection = true;
                    // Number of entities in an OSM file is unknown, so derive progress from the number of bytes read.
                    // Wrapping in buffered input stream should reduce number of progress updates.
                    osm.readPbf(ProgressInputStream.forFileItem(fi, progressListener));
                    // osm.readPbf(new BufferedInputStream(fi.getInputStream()));
                    Envelope osmBounds = new Envelope();
                    for (Node n : osm.nodes.values()) {
                        osmBounds.expandToInclude(n.getLon(), n.getLat());
                    }
                    osm.close();
                    checkWgsEnvelopeSize(osmBounds, "OSM data");
                    // Store the source OSM file. Note that we're not storing the derived MapDB file here.
                    fileStorage.moveIntoStorage(OSMCache.getKey(bundle.osmId), fi.getStoreLocation());
                }

                if (bundle.feedGroupId == null) {
                    // Process uploaded GTFS files
                    bundle.feedGroupId = new ObjectId().toString();

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
                        File tempErrorJsonFile = new File(tempDbFile.getAbsolutePath() + ".error.json");

                        GTFSFeed feed = GTFSFeed.newWritableFile(tempDbFile);
                        feed.progressListener = progressListener;
                        feed.loadFromFile(zipFile, new ObjectId().toString());

                        // Perform any more complex validation that requires cross-table checks.
                        new PostLoadValidator(feed).validate();

                        // Find and validate the extents of the GTFS, defined by all stops in the feed.
                        for (Stop s : feed.stops.values()) {
                            bundleBounds.expandToInclude(s.stop_lon, s.stop_lat);
                        }
                        try {
                            checkWgsEnvelopeSize(bundleBounds, "GTFS data");
                        } catch (IllegalArgumentException iae) {
                            // Convert envelope size or antimeridian crossing exceptions to feed import errors.
                            // Out of range lat/lon values will throw DataSourceException and bundle import will fail.
                            // Envelope size or antimeridian crossing will throw IllegalArgumentException. We want to
                            // soft-fail on these because some feeds contain small amounts of long-distance service
                            // which may extend far beyond the analysis area without causing problems.
                            feed.errors.add(new GeneralError("stops", -1, null, iae.getMessage()));
                        }

                        // Populate the metadata while the feed is still open.
                        // This must be done after all errors have been added to the feed.
                        // TODO also get service range, hours per day etc. and error summary (and complete error JSON).
                        Bundle.FeedSummary feedSummary = new Bundle.FeedSummary(feed, bundle.feedGroupId);
                        bundle.feeds.add(feedSummary);

                        if (bundle.serviceStart.isAfter(feedSummary.serviceStart)) {
                            bundle.serviceStart = feedSummary.serviceStart;
                        }

                        if (bundle.serviceEnd.isBefore(feedSummary.serviceEnd)) {
                            bundle.serviceEnd = feedSummary.serviceEnd;
                        }

                        // Save all errors to a file.
                        try (Writer jsonWriter = new FileWriter(tempErrorJsonFile)) {
                            JsonUtil.objectMapper.writeValue(jsonWriter, feed.errors);
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                        // Release some memory after we've summarized the errors to Mongo and a JSON file.
                        feed.errors.clear();

                        // Flush db files to disk
                        feed.close();

                        // Ensure all files have been stored.
                        fileStorage.moveIntoStorage(gtfsCache.getFileKey(feedSummary.bundleScopedFeedId, "db"), tempDbFile);
                        fileStorage.moveIntoStorage(gtfsCache.getFileKey(feedSummary.bundleScopedFeedId, "db.p"), tempDbpFile);
                        fileStorage.moveIntoStorage(gtfsCache.getFileKey(feedSummary.bundleScopedFeedId, "zip"), feedFile);
                        fileStorage.moveIntoStorage(gtfsCache.getFileKey(feedSummary.bundleScopedFeedId, "error.json"), tempErrorJsonFile);
                    }
                    // Set legacy progress field to indicate that all feeds have been loaded.
                    bundle.feedsComplete = bundle.totalFeeds;

                    bundle.north = bundleBounds.getMaxY();
                    bundle.south = bundleBounds.getMinY();
                    bundle.east = bundleBounds.getMaxX();
                    bundle.west = bundleBounds.getMinX();
                }
                writeNetworkConfigToCache(bundle);
                bundle.status = Bundle.Status.DONE;
              } catch (Throwable t) {
                LOG.error("Error creating bundle", t);
                bundle.status = Bundle.Status.ERROR;
                bundle.statusText = ExceptionUtils.shortAndLongString(t);
                // Rethrow the problem so the task scheduler will attach it to the task with state ERROR.
                // Eventually this whole catch and finally clause should be handled generically up in the task scheduler.
                throw t;
              } finally {
                // ID and create/update times are assigned here when we push into Mongo.
                Persistence.bundles.modifiyWithoutUpdatingLock(bundle);
              }
        }));
        // TODO do we really want to return the bundle here? It should not be needed until the background work is done.
        // We could instead return the WorkProduct instance (or BaseModel instance or null) from TaskActions.
        return bundle;
    }

    private void writeNetworkConfigToCache (Bundle bundle) throws IOException {
        TransportNetworkConfig networkConfig = new TransportNetworkConfig();
        networkConfig.osmId = bundle.osmId;
        networkConfig.gtfsIds = bundle.feeds.stream().map(f -> f.bundleScopedFeedId).collect(Collectors.toList());

        String configFileName = bundle._id + ".json";
        File configFile = FileUtils.createScratchFile("json");
        JsonUtil.objectMapper.writeValue(configFile, networkConfig);

        FileStorageKey key = new FileStorageKey(BUNDLES, configFileName);
        fileStorage.moveIntoStorage(key, configFile);
    }

    private Bundle deleteBundle (Request req, Response res) throws IOException {
        Bundle bundle = Persistence.bundles.removeIfPermitted(req.params("_id"), UserPermissions.from(req));
        FileStorageKey key = new FileStorageKey(BUNDLES, bundle._id + ".zip");
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
