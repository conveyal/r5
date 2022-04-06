package com.conveyal.analysis.util;

import gnu.trove.map.TObjectIntMap;
import gnu.trove.map.TObjectLongMap;
import gnu.trove.map.hash.TObjectIntHashMap;
import gnu.trove.map.hash.TObjectLongHashMap;
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

/**
 * This is a proof of concept / prototype of better GTFSCache behavior. See issue #799 for context.
 * Our requirements are:
 * Do not leave a feed open when done using it.
 * Do not close a feed while another thread is still using it.
 * Hard limit on total number of feeds open at once:
 * get() blocks when full until a feed can be evicted (has no users).
 * Only one GTFSFeed can be open for each key at a time.
 *
 * This is just a draft and would need a serious audit for concurrency issues.
 *
 * To simplify (while losing generality):
 * reference counting logic could be pushed down into GTFSFeed itself.
 * ReferenceCountingCache logic could be pulled up into GTFSCache.
 */
public abstract class ReferenceCountingCache<K, V> {

    private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    /** The maximum number of GTFSFeeds to keep open at once. Small for testing purposes. */
    private static final int MAX_SIZE = 2;

    private final Lock lock = new ReentrantLock();

    private final Condition canEvict = lock.newCondition();

    private final Map<K, V> map = new HashMap<K, V>();

    private final Set<K> evictableKeys = new HashSet<>();

    private final TObjectIntMap<K> usesOfKey = new TObjectIntHashMap<>();

    private final TObjectLongMap<K> lastGetTime = new TObjectLongHashMap<>();

    // Locking is very non-granular but it may be a good idea to accept some degradation in performance for behavior
    // that is more predictable and maintainable (easier to model mentally).
    // For now loading or eviction methods must be very quick as they will will block all other access.
    public RefCount get (K key) {
        lock.lock();
        try {
            while (!map.containsKey(key) && noSlotsAvailable()) {
                canEvict.await();
            }
            V value = map.get(key);
            if (value == null) {
                value = loadValue(key);
                map.put(key, value);
            }
            lastGetTime.put(key, System.currentTimeMillis());
            usesOfKey.adjustOrPutValue(key, 1, 1);
            evictableKeys.remove(key);
            LOG.info("Uses by key: {}", usesOfKey);
            return new RefCount(key, value);
        } catch (Exception e) {
            throw new RuntimeException("Exception:", e);
        } finally {
            // TODO signal to handle the case where one thread is waiting on key k and another thread causes k to load
            lock.unlock();
        }
    }

    // Only call this while you have the lock
    private boolean noSlotsAvailable() {
        evictAsNeeded();
        return map.size() >= MAX_SIZE;
    }

    // Only call this while you have the lock
    private void evictAsNeeded() {
        // TODO scan over last get times and set uses to zero for keys whose time is too far in the past
        if (map.size() >= MAX_SIZE) {
            K keyToEvict = evictableKeys.iterator().next();
            if (keyToEvict == null) {
                throw new RuntimeException("Expected eviction candidates but found none.");
            }
            usesOfKey.remove(keyToEvict);
            lastGetTime.remove(keyToEvict);
            evictableKeys.remove(keyToEvict);
            V valueToEvict = map.remove(keyToEvict);
            if (valueToEvict == null) {
                throw new RuntimeException("Expected eviction value but found none.");
            }
            LOG.info("Evicted {} -> {}", keyToEvict, valueToEvict);
            postEvictCleanup(keyToEvict, valueToEvict);
        }
    }

    /**
     * After calling get() to retrieve a value, the caller must call this method when it's finished using the value.
     */
    public void release (K key) {
        lock.lock();
        int remainingUses = usesOfKey.adjustOrPutValue(key, -1, 0);
        // Don't evict here even if there are currently zero uses - evict only when we need a new slot for a new key.
        // But we signal other threads who may be awaiting that condition to perform eviction.
        if (remainingUses < 1) {
            LOG.info("No remaining uses of {}, signaling potential for eviction.", key);
            evictableKeys.add(key);
            canEvict.signal();
        }
        LOG.info("Uses by key: {}", usesOfKey);
        lock.unlock();
    }

    public /* non-static */ class RefCount implements AutoCloseable {
        final K key;
        final V value;
        public RefCount (K key, V value) {
            this.key = key;
            this.value = value;
        }
        @Override
        public void close () {
            release(key);
        }
        public V get() {
            return value;
        }
    }

    public abstract V loadValue (K key);

    public abstract void postEvictCleanup (K key, V value);

}
