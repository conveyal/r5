package com.conveyal.gtfs;

import com.conveyal.analysis.components.Component;
import com.conveyal.file.FileStorage;
import com.conveyal.file.FileStorageKey;
import com.conveyal.file.FileUtils;
import com.conveyal.gtfs.model.Pattern;
import com.conveyal.gtfs.model.Route;
import com.conveyal.gtfs.model.Stop;
import com.conveyal.r5.common.GeometryUtils;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.github.benmanes.caffeine.cache.RemovalListener;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.index.strtree.STRtree;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import java.io.File;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import static com.conveyal.file.FileCategory.BUNDLES;
import static com.google.common.base.Preconditions.checkState;

/**
 * Cache for GTFSFeed objects, a disk-backed (MapDB) representation of data from one GTFS feed. The source GTFS
 * feeds and backing MapDB files are themselves cached in the file store for reuse by later deployments and worker
 * machines. GTFSFeeds may be evicted from the in-memory cache, at which time they will be closed. Any code continuing
 * to hold a reference to the evicted GTFSFeed will then fail if it tries to access the closed MapDB. The exact eviction
 * policy is discussed in Javadoc on the class fields and methods.
 */
public class GTFSCache implements Component {

    private static final Logger LOG = LoggerFactory.getLogger(GTFSCache.class);

    public final FileStorage fileStorage;

    private final LoadingCache<String, GTFSFeed> cache;

    // The following two caches hold spatial indexes of GTFS geometries for generating Mapbox vector tiles, one spatial
    // index per feed keyed on BundleScopedFeedId. They could potentially be combined such that cache values are a
    // compound type holding two indexes, or cache values are a single index containing a mix of different geometry
    // types that are filtered on iteration. They could also be integreated into the GTFSFeed values of the main
    // GTFSCache#cache. However GTFSFeed is already a very long class, and we may want to tune eviction parameters
    // separately for GTFSFeed and these indexes. While GTFSFeeds are expected to incur constant memory use, the
    // spatial indexes are potentially unlimited in size and we may want to evict them faster or limit their quantity.
    // We have decided to keep them as separate caches until we're certain of the chosen eviction tuning parameters.

    /** A cache of spatial indexes of TripPattern shapes, keyed on the BundleScopedFeedId. */
    public final GeometryCache<LineString> patternShapes;

    /** A cache of spatial indexes of transit stop locations, keyed on the BundleScopedFeedId. */
    public final GeometryCache<Point> stops;

    public GTFSCache (FileStorage fileStorage) {
        LOG.info("Initializing the GTFS cache...");
        this.fileStorage = fileStorage;
        this.cache = makeCaffeineCache();
        this.patternShapes = new GeometryCache<>(this::buildShapesIndex);
        this.stops = new GeometryCache<>(this::buildStopsIndex);
    }

    public static String cleanId(String id) {
        return id.replaceAll("[^A-Za-z0-9_]", "-");
    }

    /**
     * Each GTFSFeed instance is an abstraction over a set of MapDB tables backed by disk files. We cannot allow more
     * than instance actively representing the same feed, as this would corrupt the underlying disk files.
     *
     * A caller can hold a reference to a GTFSFeed for an indefinite amount of time. If during that time the GTFSFeed is
     * evicted from the cache then requested again, a new GTFSFeed instance would be created by the CacheLoader and
     * connected to the same backing files. Therefore our eviction listener closes the MapDB when it is evicted.
     * This means that callers still holding references will no longer be able to use their reference, as it is closed.
     * Closing on eviction is the only way to safely avoid file corruption, and in practice poses no problems as this
     * system is only used for exposing GTFS contents over a GraphQL API. At worst an API call would fail and have to
     * be re-issued by the client.
     *
     * Another approach is to use SoftReferences for the cache values, which will not be evicted if they are referenced
     * elsewhere. This would eliminate the problem of callers holding references to closed GTFSFeeds, however eviction
     * is then based on heap memory demand, as perceived by the garbage collector. This is quite unpredictable - it
     * could decide to evict ten feeds that are unreferenced but last used only seconds ago, when memory could have
     * been freed by a GC pass. Caffeine cache documentation recommends using "the more predictable maximum cache
     * size" instead. Size-based eviction will "try to evict entries that have not been used recently or very often".
     * Maximum size eviction can be combined with time-based eviction.
     *
     * The memory cost of each open MapDB should just be the size-limited "instance cache", and any off-heap address
     * space allocated to memory-mapped files. I believe this cost should be roughly constant per feed. The
     * maximum size interacts with the instance cache size of MapDB itself, and whether MapDB memory is on or off heap.
     */
    private LoadingCache<String, GTFSFeed> makeCaffeineCache () {
        RemovalListener<String, GTFSFeed> removalListener = (uniqueId, feed, cause) -> {
            LOG.info("Evicting feed {} from GTFSCache and closing MapDB file. Reason: {}", uniqueId, cause);
            // Close DB to avoid leaking (off-heap allocated) memory for MapDB object cache, and MapDB corruption.
            feed.close();
        };
        return Caffeine.newBuilder()
                .maximumSize(20L)
                .expireAfterAccess(60L, TimeUnit.MINUTES)
                .removalListener(removalListener)
                .build(this::retrieveAndProcessFeed);
    }

    public static FileStorageKey getFileKey (String id, String extension) {
        return new FileStorageKey(BUNDLES, String.join(".", cleanId(id), extension));
    }

    /**
     * Retrieve the feed with the given id, lazily creating it if it's not yet loaded or built. This is expected to
     * always return a non-null GTFSFeed. If it can't it will always throw an exception with a cause. The returned feed
     * must be closed manually to avoid corruption, so it's preferable to have a single synchronized component managing
     * when files shared between threads are opened and closed.
     */
    public @Nonnull GTFSFeed get(String id) {
        GTFSFeed feed = cache.get(id);
        // The cache can in principle return null, but only if its loader method returns null.
        // This should never happen in normal use - the loader should be revised to throw a clear exception.
        if (feed == null) throw new IllegalStateException("Cache should always return a feed or throw an exception.");
        // The feedId of the GTFSFeed objects may not be unique - we can have multiple versions of the same feed
        // covering different time periods, uploaded by different users. Therefore we record another ID here that is
        // known to be unique across the whole application - the ID used to fetch the feed.
        feed.uniqueId = id;
        return feed;
    }

    /** This method should only ever be called by the cache loader. */
    private @Nonnull GTFSFeed retrieveAndProcessFeed(String bundleScopedFeedId) throws GtfsLibException {
        FileStorageKey dbKey = getFileKey(bundleScopedFeedId, "db");
        FileStorageKey dbpKey = getFileKey(bundleScopedFeedId, "db.p");
        if (fileStorage.exists(dbKey) && fileStorage.exists(dbpKey)) {
            // Ensure both MapDB files are local, pulling them down from remote storage as needed.
            fileStorage.getFile(dbKey);
            fileStorage.getFile(dbpKey);
            try {
                return GTFSFeed.reopenReadOnly(fileStorage.getFile(dbKey));
            } catch (GtfsLibException e) {
                if (e.getCause().getMessage().contains("Could not set field value: priority")) {
                    // Swallow exception and fall through - rebuild bad MapDB and upload to S3.
                    LOG.warn("Detected poisoned MapDB containing GTFSError.priority serializer. Rebuilding.");
                } else {
                    throw e;
                }
            }
        }
        FileStorageKey zipKey = getFileKey(bundleScopedFeedId, "zip");
        if (!fileStorage.exists(zipKey)) {
            throw new GtfsLibException("Original GTFS zip file could not be found: " + zipKey);
        }
        // This code path is rarely run because we usually pre-build GTFS MapDBs in bundleController and cache them.
        // This will only be run when the resultant MapDB has been deleted or is otherwise unavailable.
        LOG.debug("Building or rebuilding MapDB from original GTFS ZIP file at {}...", zipKey);
        try {
            File tempDbFile = FileUtils.createScratchFile("db");
            File tempDbpFile = new File(tempDbFile.getAbsolutePath() + ".p");
            // An unpleasant hack since we do not have separate references to the GTFS feed ID and Bundle ID here,
            // only a concatenation of the two joined with an underscore. We have to force-override feed ID because
            // references to its contents (e.g. in scenarios) are scoped only by the feed ID not the bundle ID.
            // The bundle ID is expected to always be an underscore-free UUID, but old feed IDs may contain underscores
            // (yielding filenames like old_feed_id_bundleuuid) so we look for the last underscore as a split point.
            // GTFS feeds may now be referenced by multiple bundles with different IDs, so the last part of the file
            // name is rather arbitrary - it's just the bundleId with which this feed was first associated.
            // We don't really need to scope newer feeds by bundleId since they now have globally unique UUIDs.
            int splitIndex = bundleScopedFeedId.lastIndexOf("_");
            checkState(splitIndex > 0 && splitIndex < bundleScopedFeedId.length() - 1,
                "Expected underscore-joined feedId and bundleId.");
            String feedId = bundleScopedFeedId.substring(0, splitIndex);
            GTFSFeed.newFileFromGtfs(tempDbFile, fileStorage.getFile(zipKey), feedId);
            // The DB file should already be closed and flushed to disk.
            // Put the DB and DB.p files in local cache, and mirror to remote storage if configured.
            fileStorage.moveIntoStorage(dbKey, tempDbFile);
            fileStorage.moveIntoStorage(dbpKey, tempDbpFile);
            // Reopen the feed in its new location, enforcing read-only access to avoid file corruption.
            return GTFSFeed.reopenReadOnly(fileStorage.getFile(dbKey));
        } catch (Exception e) {
            throw new GtfsLibException("Error loading zip file for GTFS feed: " + zipKey, e);
        }
    }

    /** CacheLoader implementation making spatial indexes of stop pattern shapes for a single feed. */
    private void buildShapesIndex (String bundleScopedFeedId, STRtree tree) {
        final long startTimeMs = System.currentTimeMillis();
        final GTFSFeed feed = this.get(bundleScopedFeedId);
        // This is huge, we can instead map from envelopes to tripIds, but re-fetching those trips is slow
        LOG.info("{}: indexing {} patterns", feed.feedId, feed.patterns.size());
        for (Pattern pattern : feed.patterns.values()) {
            Route route = feed.routes.get(pattern.route_id);
            String exemplarTripId = pattern.associatedTrips.get(0);
            LineString wgsGeometry = feed.getTripGeometry(exemplarTripId);
            if (wgsGeometry == null) {
                // Not sure why some of these are null.
                continue;
            }
            Map<String, Object> userData = new HashMap<>();
            userData.put("id", pattern.pattern_id);
            userData.put("name", pattern.name);
            userData.put("routeId", route.route_id);
            userData.put("routeName", route.route_long_name);
            userData.put("routeColor", Objects.requireNonNullElse(route.route_color, "000000"));
            userData.put("routeType", route.route_type);
            wgsGeometry.setUserData(userData);
            tree.insert(wgsGeometry.getEnvelopeInternal(), wgsGeometry);
        }
        LOG.info("Created vector tile spatial index for patterns in feed {} ({})", bundleScopedFeedId, Duration.ofMillis(System.currentTimeMillis() - startTimeMs));
    }

    /**
     * CacheLoader implementation making spatial indexes of transit stops for a single feed.
     * This is inefficient, TODO specialized spatial index to bin points into mercator tiles (like hashgrid).
     */
    private void buildStopsIndex (String bundleScopedFeedId, STRtree tree) {
        final long startTimeMs = System.currentTimeMillis();
        final GTFSFeed feed = this.get(bundleScopedFeedId);
        LOG.info("{}: indexing {} stops", feed.feedId, feed.stops.size());
        for (Stop stop : feed.stops.values()) {
            // To match existing GTFS API, include only stop objects that have location_type 0.
            // All other location_types (station, entrance, generic node, boarding area) are skipped.
            // Missing (NaN) coordinates will confuse the spatial index and cascade hundreds of errors.
            if (stop.location_type != 0 || !Double.isFinite(stop.stop_lat) || !Double.isFinite(stop.stop_lon)) {
                continue;
            }
            Envelope stopEnvelope = new Envelope(stop.stop_lon, stop.stop_lon, stop.stop_lat, stop.stop_lat);
            Point point = GeometryUtils.geometryFactory.createPoint(new Coordinate(stop.stop_lon, stop.stop_lat));

            Map<String, Object> properties = new HashMap<>();
            properties.put("feedId", stop.feed_id);
            properties.put("id", stop.stop_id);
            properties.put("name", stop.stop_name);
            properties.put("lat", stop.stop_lat);
            properties.put("lon", stop.stop_lon);

            point.setUserData(properties);
            tree.insert(stopEnvelope, point);
        }
        LOG.info("Created spatial index for stops in feed {} ({})", bundleScopedFeedId, Duration.ofMillis(System.currentTimeMillis() - startTimeMs));
    }

}