package com.conveyal.r5.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * We lazy-load and lazy-build a lot of things, including TransportNetworks, grid linkages, and
 * distance tables. We used Guava LoadingCaches for these, so subsequent requests would block and
 * share a single instance of the requested object. But these operations can be slow - in the tens
 * of minutes sometimes. It's not reasonable to silently hold HTTP connections open that long,
 * leaving the client uninformed as to whether something has crashed.
 *
 * <p>We instead prefer to fail fast, informing the client that the object is being loaded or
 * computed. Guava does not cleanly support such cases: https://github.com/google/guava/issues/1350
 *
 * <p>This is actually more of a Map than a Cache since it doesn't support eviction yet. Maybe it's
 * an AsyncLazyLoadingConcurrentMap.
 *
 * <p>We could use a ConcurrentMap internally, except that putIfAbsent doesn't allow us to easily
 * trigger an additional task enqueue operation or retain a reference to the value that was put by
 * default. Consider whether you really want eviction - if not, requesting status for a key and
 * requesting value can be done with separate methods, since status always proceeds in a single
 * direction with the final stage being "value is present in map".
 *
 * <p>Potential problem: if we try to return immediately saying whether the needed data are
 * available, there are some cases where preparing the reqeusted object might take only a few
 * hundred milliseconds or less. In that case then we don't want the caller to have to re-poll. In
 * this case a Future.get() with timeout is good.
 *
 * <p>Created by abyrd on 2018-09-14
 */
public abstract class AsyncLoader<K, V> {

    private static final Logger LOG = LoggerFactory.getLogger(AsyncLoader.class);

    /**
     * This map holds a loading progress object for each key, which will contain the requested
     * object when complete.
     */
    private Map<K, LoaderState<V>> map = new HashMap<>();

    /**
     * Each cache has its own executor, so tiny things like loading grids are not held up by slower
     * things like linking or distance calculations. This also avoids deadlocks where one cache
     * loads values from another cache as part of its building process, but the sub-task can never
     * happen because the parent task is hogging a thread. We may want to consider using the single
     * TaskScheduler component which has separate heavy and light executors.
     */
    private Executor executor = Executors.newFixedThreadPool(2);

    /**
     * Represents the status of the requested object, its position in the build cycle. This could
     * also be represented with special (negative) values in the percentComplete field.
     */
    public enum Status {
        WAITING, // The runnable for computing the requested object is enqueued but not yet
                 // executing.
        BUILDING, // The runnable for computing the requested object is currently executing, see
                  // percentComplete.
        PRESENT, // The runnable has completed successfully, and the resulting value is available.
        ERROR // The runnable failed for some reason, see message field for explanation.
    }

    /**
     * The fields will not be updated as the status of the fetch operation proceeds - this is not a
     * "future" or a "promise", just an immutable wrapper class for communicating information at the
     * time the get() call is made. You have to repeat calls to get new responses that might contain
     * the needed value. i.e. this is a polling approach not an event driven approach to
     * asynchronous loading. The reason for that is that it's typically accessed over an HTTP API.
     */
    public static class LoaderState<V> {
        public final Status status;
        public final String message;
        public final int percentComplete;
        public final V value;
        public final Throwable throwable;

        private LoaderState(Status status, String message, int percentComplete, V value) {
            this.status = status;
            this.message = message;
            this.percentComplete = percentComplete;
            this.value = value;
            this.throwable = null;
        }

        private LoaderState(Throwable throwable) {
            this.status = Status.ERROR;
            this.message = throwable.toString();
            this.percentComplete = 0;
            this.value = null;
            this.throwable = throwable;
        }

        @Override
        public String toString() {
            return String.format(
                    "%s %s (%d%% complete)", status.toString(), message, percentComplete);
        }
    }

    /**
     * Attempt to fetch the value for the supplied key. If the value is not yet present, and not yet
     * being computed / fetched, enqueue a task to do so. Return a response that reports status, and
     * may or may not contain the value.
     */
    public LoaderState<V> get(K key) {
        LoaderState<V> state = null;
        boolean enqueueLoadTask = false;
        synchronized (map) {
            state = map.get(key);
            if (state == null) {
                // Only enqueue a task to load the value for this key if another call hasn't already
                // done it.
                state = new LoaderState<V>(Status.WAITING, "Enqueued task...", 0, null);
                map.put(key, state);
                enqueueLoadTask = true;
            }
        }
        // TODO maybe use futures and get() with timeout, so fast scenario applications don't
        // require re-polling
        // Enqueue task outside the above block (synchronizing the fewest lines possible).
        if (enqueueLoadTask) {
            executor.execute(
                    () -> {
                        setProgress(key, 0, "Starting...");
                        try {
                            V value = buildValue(key);
                            synchronized (map) {
                                map.put(key, new LoaderState(Status.PRESENT, null, 100, value));
                            }
                        } catch (Throwable t) {
                            // It's essential to trap Throwable rather than just Exception.
                            // Otherwise the executor threads can be killed by any Error that
                            // happens, stalling the executor.
                            setError(key, t);
                            LOG.error(ExceptionUtils.asString(t));
                        }
                    });
        }
        return state;
    }

    /**
     * Override this method in concrete subclasses to specify the logic to build/calculate/fetch a
     * value. Implementations may call setProgress to report progress on long operations. Throw an
     * exception to indicate an error has occurred and the building process cannot complete. It's
     * not entirely clear this should return a value - might be better to call setValue within the
     * overridden method, just as we call setProgress or setError.
     */
    protected abstract V buildValue(K key) throws Exception;

    /** Call this method inside the buildValue method to indicate progress. */
    public void setProgress(K key, int percentComplete, String message) {
        synchronized (map) {
            map.put(key, new LoaderState(Status.BUILDING, message, percentComplete, null));
        }
    }

    /**
     * Call this method inside the buildValue method to indicate that an unrecoverable error has
     * happened. FIXME this will permanently associate an error with the key. No further attempt
     * will ever be made to create the value.
     */
    protected void setError(K key, Throwable throwable) {
        synchronized (map) {
            map.put(key, new LoaderState(throwable));
        }
    }
}
