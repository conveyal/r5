package com.conveyal.gtfs;

import com.conveyal.file.FileStorage;
import com.conveyal.file.FileStorageKey;
import com.conveyal.file.FileUtils;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.github.benmanes.caffeine.cache.RemovalListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipFile;

/**
 * Cache for GTFSFeed objects, a disk-backed (MapDB) representation of data from one GTFS feed. The source GTFS
 * feeds and backing MapDB files are themselves cached in the file store for reuse by later deployments and worker
 * machines. GTFSFeeds may be evicted from the in-memory cache, at which time they will be closed. Any code continuing
 * to hold a reference to the evicted GTFSFeed will then fail if it tries to access the closed MapDB. The exact eviction
 * policy is discussed in Javadoc on the class fields and methods.
 */
public class GTFSCache {
    private static final Logger LOG = LoggerFactory.getLogger(GTFSCache.class);
    private final LoadingCache<String, GTFSFeed> cache;

    public final String bucket;
    public final FileStorage fileStore;

    public interface Config {
        String bundleBucket ();
    }

    public static String cleanId(String id) {
        return id.replaceAll("[^A-Za-z0-9_]", "-");
    }

    public GTFSCache(FileStorage fileStore, Config config) {
        LOG.info("Initializing the GTFS cache...");
        this.fileStore = fileStore;
        this.bucket = config.bundleBucket();
        this.cache = makeCaffeineCache();
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

    public FileStorageKey getFileKey (String id, String extension) {
        return new FileStorageKey(this.bucket, String.join(".", cleanId(id), extension));
    }

    public void add (String id, GTFSFeed feed) {
        cache.put(id, feed);
    }

    public GTFSFeed get(String id) {
        GTFSFeed feed = cache.get(id);
        // The feedId of the GTFSFeed objects may not be unique - we can have multiple versions of the same feed
        // covering different time periods, uploaded by different users. Therefore we record another ID here that is
        // known to be unique across the whole application - the ID used to fetch the feed.
        feed.uniqueId = id;
        return feed;
    }

    // This should only ever be called by the cache loader. The returned feed must be closed, and
    // it's preferable to have a single component managing when files shared between threads are opened and closed.
    private GTFSFeed retrieveAndProcessFeed(String id) {
        FileStorageKey dbKey = getFileKey(id, "db");
        FileStorageKey dbpKey = getFileKey(id, "db.p");

        if (fileStore.exists(dbKey) && fileStore.exists(dbpKey)) {
            // Ensure both files are local
            fileStore.getFile(dbpKey);
            return new GTFSFeed(fileStore.getFile(dbKey));
        }

        FileStorageKey zipKey = getFileKey(id, "zip");
        LOG.debug("Building or rebuilding MapDB from original GTFS ZIP file at {}...", zipKey);
        if (fileStore.exists(zipKey)) {
            try {
                File tempDbFile = FileUtils.createScratchFile("db");
                File tempDbpFile = new File(tempDbFile.getAbsolutePath() + ".p");
                ZipFile zipFile = new ZipFile(fileStore.getFile(zipKey));

                GTFSFeed feed = new GTFSFeed(tempDbFile);
                feed.loadFromFile(zipFile);
                feed.findPatterns();

                // Close the DB and flush to disk before we start moving and copying files around.
                feed.close();

                // Ensure the DB and DB.p files have been fully stored.
                fileStore.moveIntoStorage(dbKey, tempDbFile);
                fileStore.moveIntoStorage(dbpKey, tempDbpFile);

                return new GTFSFeed(fileStore.getFile(dbKey));
            } catch (Exception e) {
                LOG.error("Error loading Zip file for GTFS Feed from {}", zipKey, e);
                throw new RuntimeException(e);
            }
        } else {
            LOG.error("Original GTFS ZIP for {} could not be found.", zipKey);
        }

        LOG.error("GTFS Feed {} could not be loaded.", id);
        return null;
    }

}