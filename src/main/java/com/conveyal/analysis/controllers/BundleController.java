package com.conveyal.analysis.controllers;

import com.conveyal.analysis.AnalysisServerException;
import com.conveyal.analysis.UserPermissions;
import com.conveyal.analysis.components.TaskScheduler;
import com.conveyal.analysis.models.Bundle;
import com.conveyal.analysis.persistence.AnalysisDB;
import com.conveyal.analysis.util.HttpUtils;
import com.conveyal.analysis.util.JsonUtil;
import com.conveyal.file.FileStorage;
import com.conveyal.file.FileStorageKey;
import com.conveyal.file.FileUtils;
import com.conveyal.gtfs.GTFSCache;
import com.conveyal.gtfs.GTFSFeed;
import com.conveyal.gtfs.error.GeneralError;
import com.conveyal.gtfs.model.Stop;
import com.conveyal.gtfs.validator.PostLoadValidator;
import com.conveyal.osmlib.Node;
import com.conveyal.osmlib.OSM;
import com.conveyal.r5.analyst.cluster.TransportNetworkConfig;
import com.conveyal.r5.analyst.progress.ProgressInputStream;
import com.conveyal.r5.analyst.progress.ProgressListener;
import com.conveyal.r5.analyst.progress.Task;
import com.conveyal.r5.streets.OSMCache;
import com.conveyal.r5.util.ExceptionUtils;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
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
import java.io.UnsupportedEncodingException;
import java.time.LocalDate;
import java.util.ArrayList;
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
    private final FileStorage fileStorage;
    private final TaskScheduler taskScheduler;
    private final AnalysisDB db;

    public BundleController(AnalysisDB db, FileStorage fileStorage, TaskScheduler taskScheduler) {
        this.db = db;
        this.fileStorage = fileStorage;
        this.taskScheduler = taskScheduler;
    }

    // INTERFACE METHOD

    @Override
    public void registerEndpoints(Service sparkService) {
        sparkService.path("/api/bundle", () -> {
            sparkService.post("", this::create, toJson);
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
    private ObjectNode create(Request req, Response res) {
        // Do some initial synchronous work setting up the bundle to fail fast if the request is bad.
        final var files = HttpUtils.getRequestFiles(req.raw());
        final var userPermissions = UserPermissions.from(req);
        final var bundle = parseBundleFromRequestFiles(userPermissions, files);

        // Form data parsed, insert to MongoDB and queue the heavy work.
        db.bundles.insert(bundle);

        // Submit all slower work for asynchronous processing on the backend, then immediately return the partially
        // constructed bundle from the HTTP handler. Process OSM first, then each GTFS feed sequentially.
        var task = Task.create("Processing bundle " + bundle.name)
                .forUser(userPermissions)
                .setHeavy(true)
                .withWorkProduct(BUNDLE, bundle._id, bundle.regionId)
                .withAction(progressListener -> {
                    try {
                        if (bundle.osmId == null) {
                            processOsm(bundle, (DiskFileItem) files.get("osm").get(0), progressListener);
                        }

                        if (bundle.feedGroupId == null) {
                            processFeedGroup(bundle, files, progressListener);
                        }

                        writeNetworkConfigToCache(bundle);
                        bundle.status = Bundle.Status.DONE;
                        db.bundles.replaceOne(bundle);
                    } catch (Throwable t) {
                        LOG.error("Error creating bundle", t);
                        db.bundles.collection.updateOne(
                                Filters.eq("_id", bundle._id),
                                Updates.combine(
                                        Updates.set("status", Bundle.Status.ERROR),
                                        Updates.set("statusText", ExceptionUtils.shortAndLongString(t))
                                )
                        );
                        // Rethrow the problem so the task scheduler will attach it to the task with state ERROR.
                        // Eventually this whole catch and finally clause should be handled generically up in the task scheduler.
                        throw t;
                    }
                });
        taskScheduler.enqueue(task);

        // Return the newly created bundle _id
        return JsonUtil.objectNode().put("bundleId", bundle._id);
    }

    private boolean deleteBundle(Request req, Response res) {
        var result = db.bundles.deleteByIdParamIfPermitted(req);
        var bundleId = req.params("_id");
        // TODO: check if other bundles use this feed and bundle group
        // FileStorageKey key = new FileStorageKey(BUNDLES, bundleId + ".zip");
        // fileStorage.delete(key);

        return result.wasAcknowledged();
    }

    // HELPERS

    private Bundle parseBundleFromRequestFiles(UserPermissions userPermissions, Map<String, List<FileItem>> files) {
        final Bundle bundle = new Bundle(userPermissions);
        try {
            bundle.name = files.get("bundleName").get(0).getString("UTF-8");
            bundle.regionId = files.get("regionId").get(0).getString("UTF-8");

            if (files.get("osmId") != null) {
                bundle.osmId = files.get("osmId").get(0).getString("UTF-8");
                Bundle bundleWithOsm = db.bundles.findPermitted(Filters.eq("osmId", bundle.osmId), userPermissions).first();
                if (bundleWithOsm == null) {
                    throw AnalysisServerException.badRequest("Selected OSM does not exist.");
                }
            }

            if (files.get("feedGroupId") != null) {
                bundle.feedGroupId = files.get("feedGroupId").get(0).getString("UTF-8");
                Bundle bundleWithFeed = db.bundles.findPermitted(Filters.eq("feedGroupId", bundle.feedGroupId), userPermissions).first();
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
        } catch (UnsupportedEncodingException e) {
            throw new AnalysisServerException(e, "Error parsing form fields.");
        }
        return bundle;
    }

    private void processOsm(Bundle bundle, DiskFileItem fileItem, ProgressListener progressListener) {
        // Process uploaded OSM.
        bundle.osmId = new ObjectId().toString();
        // Here we perform minimal validation by loading the OSM, but don't retain the resulting MapDB.
        OSM osm = new OSM(null);
        osm.intersectionDetection = true;
        // Number of entities in an OSM file is unknown, so derive progress from the number of bytes read.
        osm.readPbf(ProgressInputStream.forFileItem(fileItem, progressListener));
        Envelope osmBounds = new Envelope();
        for (Node n : osm.nodes.values()) {
            osmBounds.expandToInclude(n.getLon(), n.getLat());
        }
        osm.close();
        checkWgsEnvelopeSize(osmBounds, "OSM data");
        // Store the source OSM file. Note that we're not storing the derived MapDB file here.
        fileStorage.moveIntoStorage(OSMCache.getKey(bundle.osmId), fileItem.getStoreLocation());
    }

    private void processFeedGroup(Bundle bundle, Map<String, List<FileItem>> files, ProgressListener progressListener) throws Exception {
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
            File tempErrorJsonFile = FileUtils.createScratchFile("json");

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
            JsonUtil.objectMapper.writeValue(tempErrorJsonFile, feed.errors);

            // Release some memory after we've summarized the errors to Mongo and a JSON file.
            feed.errors.clear();

            // Flush db files to disk
            feed.close();

            // Ensure all files have been stored.
            fileStorage.moveIntoStorage(GTFSCache.getFileKey(feedSummary.bundleScopedFeedId, "db"), tempDbFile);
            fileStorage.moveIntoStorage(GTFSCache.getFileKey(feedSummary.bundleScopedFeedId, "db.p"), tempDbpFile);
            fileStorage.moveIntoStorage(GTFSCache.getFileKey(feedSummary.bundleScopedFeedId, "zip"), feedFile);
            fileStorage.moveIntoStorage(GTFSCache.getFileKey(feedSummary.bundleScopedFeedId, "error.json"), tempErrorJsonFile);
        }

        // Set legacy progress field to indicate that all feeds have been loaded.
        bundle.feedsComplete = bundle.totalFeeds;

        bundle.north = bundleBounds.getMaxY();
        bundle.south = bundleBounds.getMinY();
        bundle.east = bundleBounds.getMaxX();
        bundle.west = bundleBounds.getMinX();
    }

    private void writeNetworkConfigToCache(Bundle bundle) throws IOException {
        TransportNetworkConfig networkConfig = new TransportNetworkConfig();
        networkConfig.osmId = bundle.osmId;
        networkConfig.gtfsIds = bundle.feeds.stream().map(f -> f.bundleScopedFeedId).collect(Collectors.toList());

        String configFileName = bundle._id + ".json";
        File configFile = FileUtils.createScratchFile("json");
        JsonUtil.objectMapper.writeValue(configFile, networkConfig);

        FileStorageKey key = new FileStorageKey(BUNDLES, configFileName);
        fileStorage.moveIntoStorage(key, configFile);
    }

}
