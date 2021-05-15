package com.conveyal.gtfs;

import com.conveyal.file.FileStorage;
import com.conveyal.file.FileStorageKey;
import com.conveyal.file.FileUtils;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.github.benmanes.caffeine.cache.RemovalListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import java.io.File;
import java.util.concurrent.TimeUnit;

import static com.conveyal.file.FileCategory.BUNDLES;

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

    public final FileStorage fileStorage;

    public static String cleanId(String id) {
        return id.replaceAll("[^A-Za-z0-9_]", "-");
    }

    public GTFSCache (FileStorage fileStorage) {
        LOG.info("Initializing the GTFS cache...");
        this.fileStorage = fileStorage;
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
    private @Nonnull GTFSFeed retrieveAndProcessFeed(String id) throws GtfsLibException {
        FileStorageKey dbKey = getFileKey(id, "db");
        FileStorageKey dbpKey = getFileKey(id, "db.p");
        if (fileStorage.exists(dbKey) && fileStorage.exists(dbpKey)) {
            // Ensure both MapDB files are local, pulling them down from remote storage as needed.
            fileStorage.getFile(dbKey);
            fileStorage.getFile(dbpKey);
            return GTFSFeed.reopenReadOnly(fileStorage.getFile(dbKey));
        }
        FileStorageKey zipKey = getFileKey(id, "zip");
        if (!fileStorage.exists(zipKey)) {
            throw new GtfsLibException("Original GTFS zip file could not be found: " + zipKey);
        }
        LOG.debug("Building or rebuilding MapDB from original GTFS ZIP file at {}...", zipKey);
        try {
            File tempDbFile = FileUtils.createScratchFile("db");
            File tempDbpFile = new File(tempDbFile.getAbsolutePath() + ".p");
            GTFSFeed.newFileFromGtfs(tempDbFile, fileStorage.getFile(zipKey));
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

}