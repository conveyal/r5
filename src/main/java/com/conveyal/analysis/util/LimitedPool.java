package com.conveyal.analysis.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandles;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * This is a proof of concept / prototype of better GTFSCache and GeometryCache behavior. See issue #799 for context.
 * This is a work in progress and should continue to be subjected to serious review for concurrency issues.
 *
 * Our requirements are:
 * Do not leave a feed open when done using it, but don't close it until necessary to avoid costs of re-opening.
 * Do not close a feed while another thread is still using it.
 * Hard limit on total number of feeds open at once:
 * get() blocks when full until a feed can be evicted (has no readers).
 * Only one GTFSFeed should ever be open for a particular key at a time.
 *
 * Like a Guava or Caffeine LoadingCache, values are computed when absent using a supplied laoder.
 * Here, the loader is supplied by implementing methods on a subclass.
 *
 * LimitedPool is also used to impose a readers-writer access pattern where the automatic loading or intialization
 * of the Entry is the only time the value is written to, and after get() returns all threads treat the value as
 * read-only. This should work even for values that are not inherently threadsafe. If the values also provide their own
 * locking then the callers of get() would not need to treat the returned values as read-only.
 *
 * To keep the locking (and our mental model of the locking) simple, all access to both the LimitedPool instance and
 * the Entries it contains uses a single lock across the entire LimitedPool instance.
 * The only exception is the potentially slow-moving value loading operations. These lock on the Entry instances,
 * allowing other threads to retrieve the Entry and update the reader count etc. while loading is still happening.
 *
 * For some use cases, even this fine-grained locking while loading is overkill. We could conceivably lock the
 * whole pool while individual keys are loaded or evicted. For example there is not a lot of contention for access
 * to GTFS feeds (we tend to hit a feed once and then close, even when building spatial indexes for vector tiles).
 * The process of opening or closing a GTFSFeed MapDB is not particularly slow. But other items like vector tile
 * geometry spatial indexes see a much more concurrent access, which would also cascade heavy concurrent GTFSFeed
 * access if we looked up those geometries on demand instead of holding them in memory. These geometry indexes also
 * take a long time to build (up to 10-20 seconds in really large networks), so locking the whole LimitedPool
 * while building them is not viable.
 *
 * This could be simplified (while losing generality):
 * Reference counting logic could be pushed down into Entry values themselves (possibly by making them extend Entry).
 * LimitedPool logic could be pulled up into GTFSCache (possibly by making it extend LimitedPool).
 */
public abstract class LimitedPool<K, V> {

    private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    /** The display name used to distinguish between different LimitedPools in log messages. */
    public final String logName;

    /** The maximum number of Entries to keep available and open at once. */
    private final int maxSize;

    /**
     * The lock preventing multiple threads from modifying the pool at the same time, and allowing them to block
     * waiting for a free slot when there are too many Entries.
     */
    private final Lock poolLock = new ReentrantLock();

    /** A condition that indicates a new value is available, or some values may be evicted (they have no readers). */
    private final Condition available = poolLock.newCondition();

    /** The core of the pool: a map from keys to values computed for those keys. */
    private final Map<K, Entry> map = new HashMap<>();

    /**
     * The keys that are ready to be evicted. This is just avoiding an iteration over the hashmap's values, which is
     * probably not even improving performance since this pool never has more than a few entries. In fact a small array
     * would probably be more efficient than a hashmap as the underlying data structure for the whole pool.
     */
    private final Set<K> evictableKeys = new HashSet<>();

    /** Constructor */
    public LimitedPool (String logName, int maxSize) {
        this.logName = logName;
        this.maxSize = maxSize;
    }

    /**
     * Blocking method to retrieve a single Entry of the LimitedPool by key, loading the value if necessary when space
     * is available. If multiple threads call get on the same key, only one of them should load the value, and the
     * others should wait for it to become available. The value should only be evicted after all the callers to get()
     * also call close() on the Entry, ideally via a try-with-resources block. But this eviction should be delayed
     * until space is needed for other Entries, to avoid re-loading the value if more calls come in for the same key.
     */
    public Entry get (K key) {
        Entry entry = null;
        try {
            poolLock.lock();
            // Sort-circuit: only attempt eviction if the key is not already in the map and no slots are free.
            while (!(map.containsKey(key) || map.size() < maxSize || evictOneEntry())) {
                available.await();
            }
            entry = map.get(key);
            if (entry == null) {
                LOG.debug("{} creating entry for key {}.", logName, key);
                entry = new Entry(key);
                map.put(key, entry);
            } else {
                LOG.debug("{} existing entry for key {}.", logName, key);
            }
            entry.lastGetTime = System.currentTimeMillis();
            entry.nReaders += 1;
            LOG.debug("{} nReaders now {} for key {}.", logName, entry.nReaders, key);
            evictableKeys.remove(key);
            // Signal all waiting threads so threads waiting on key k will see when another thread causes k to load.
            available.signal();
        } catch (Throwable t) {
            throw new RuntimeException("Failed to get Entry from LimitedPool.", t);
        } finally {
            poolLock.unlock();
        }
        // After unlocking the whole LimitedPool but before handing the Entry off to the caller, lock the individual
        // Entry and compute its value if no other caller has already done so.
        entry.computeValueAsNeeded();
        return entry;
    }

    /**
     * Attempt to evict one arbitrary entry that has no readers. ONLY CALL THIS WHILE YOU HAVE THE LOCK.
     * TODO least-recently used eviction, and expiry in case someone doesn't close an Entry when done reading it.
     * @return true if an entry could be evicted, false otherwise.
     */
    private boolean evictOneEntry() {
        if (evictableKeys.isEmpty()) {
            // TODO when evictableKeys.isEmpty, scan over Entries and identify keys whose time is too far in the past
            //      or just perform evictable Entry detection via a linear scan every time.
            return false;
        }
        // Will throw NoSuchElementExceptin if evictableKeys is not coherently maintained.
        K keyToEvict = evictableKeys.iterator().next();
        evictableKeys.remove(keyToEvict);
        Entry entryToEvict = map.remove(keyToEvict);
        checkNotNull(entryToEvict, "Expected evictable entry but found none for key: " + keyToEvict);
        LOG.debug("{} evicted entry for key {}", logName, keyToEvict);
        postEvictCleanup(keyToEvict, entryToEvict.value);
        return true;
    }

    /**
     * Holds a single value in the map, with associated data for sharing it among readers and planning eviction.
     * This object is returned to the user of LimitedPool when it calls get(). All fields are private, but the
     * instance provides value() and close() methods. Implements AutoCloseable so it can be used inside a
     * try-with-resources block, ensuring the number of readers is reliably decremented when it goes out of scope.
     * This could conceivably be pushed down into the values themselves, i.e. pool values could subclass this.
     *
     * The intrinsic lock on the Entry is used to lock lazy-loading of the entry's value. Other characteristics of
     * the entry such as the number of waiting or active readers must still be updated while one thread is loading the
     * value. These field changes are typically done around the same time we have the lock on the whole LimitedPool,
     * so we just perform non-granular locking on everything but the entry value itself (i.e. a thread must hold the
     * LimitedPool lock to update any Entry fields).
     */
    public class Entry implements AutoCloseable {

        private final K key;

        private V value;

        // The number of readers that are still using this value.
        // It should not be evicted until the number of readers reaches zero.
        private int nReaders = 0;

        // Used to evict the least recently used values, as well as values that were accidentally not released
        // by readers. Since we have a hard limit on the number of values, unreleased values could jam the pool.
        private long lastGetTime = 0;

        private Entry (K key) {
            this.key = key;
        }

        // It's tempting to use double-checked locking here to make this faster by not acquiring the lock when already
        // initialized. In JDK >5 this can be done with a brittle idiom using volatile fields. In our case the amount
        // of boilerplate and potential glitches probably does not justify the speedup. We just lock every single time
        // we get the entry, even though the lock only serves a purpose while first building the value. We don't need
        // to lock and initialize on every call to get the value() contained in the entry, just getting the Entry itself.
        // See Effective Java, Second Edition, Item 71 and
        // https://www.cs.umd.edu/~pugh/java/memoryModel/DoubleCheckedLocking.html
        private void computeValueAsNeeded () {
            // Lock on the Entry inner class instance - only done for the value, with other fields protected by
            // the main LimitedPool lock.
            synchronized (this) {
                if (value == null) {
                    value = loadValue(key);
                }
            }
        }

        @Override
        public void close () {
            // This lock is the field of the outer class LimitedPool.
            // Non-granular locking using the main LimitedPool lock to also guard Entry fields avoids deadlock entirely.
            // To avoid deadlock with more granular locking, all threads that need multiple locks must acquire those
            // locks in the same order, e.g. first the LimitedPool, then a particular Entry. This seems to end up
            // negating the perceived advantage of granular Entry field locking.
            poolLock.lock();
            {
                nReaders -= 1;
                // Don't evict immediately when we reach zero readers. Evict only when we need a slot for a new key.
                // But we signal the zero-readers condition to other threads who may waiting to perform eviction.
                LOG.debug("{} nReaders now {} for key {}.", logName, nReaders, key);
                if (nReaders < 1) {
                    evictableKeys.add(key);
                    available.signalAll();
                }
            }
            poolLock.unlock();
        }

        public V value () {
            return value;
        }
    }

    public abstract V loadValue (K key);

    public void postEvictCleanup (K key, V value) {}

}
