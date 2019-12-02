package com.conveyal.r5.analyst.progress;

import com.conveyal.r5.analyst.NetworkPreloader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This implementaton of ProgressListener posts updates to the NetworkPreloader about progress on preparing needed
 * inputs for a particular request.
 */
public class NetworkPreloaderProgressListener implements ProgressListener {

    // We can eventually perform console logging here rather than through separate LambdaCounters.
    // In fact the LambdaCounter is a kind of ProgressListener.
    // Perhaps allow setting the logger field so log entries reflect the calling class instead of ProgressListener.
    private static final Logger LOG = LoggerFactory.getLogger(NetworkPreloaderProgressListener.class);

    private int totalElements;

    protected String description;

    private int currentElement;

    private final NetworkPreloader networkPreloader;

    private final NetworkPreloader.Key key;

    /**
     * Every how many work units we'll post an update.
     * There is no point in doing this more often than the percentage changes.
     */
    private int updateFrequency;

    /**
     * Construct a ProgressListener that will post updated for a given key in the NetworkPreloader.
     * @param networkPreloader The NetworkPreloader instance to which we'll post updates.
     * @param key The key for the entry within the NetworkPreloader to which we'll post updates.
     */
    public NetworkPreloaderProgressListener(NetworkPreloader networkPreloader, NetworkPreloader.Key key) {
        this.networkPreloader = networkPreloader;
        this.key = key;
    }

    @Override
    public void beginTask(String description, int totalElements) {
        this.description = description;
        this.totalElements = totalElements;
        this.currentElement = 0;
        // Integer division truncates downward. Add one to ensure nonzero update frequency (avoiding divide by zero).
        this.updateFrequency = totalElements / 100 + 1;
    }

    @Override
    public synchronized void increment () {
        currentElement += 1;
        if (currentElement % updateFrequency == 0) {
            int percent = getPercentComplete();
            String status = String.format("%s... (%d%%)", description, percent);
            networkPreloader.setProgress(key, percent, status);
        }
    }

    private int getPercentComplete () {
        return (currentElement * 100) / totalElements;
    }

}
