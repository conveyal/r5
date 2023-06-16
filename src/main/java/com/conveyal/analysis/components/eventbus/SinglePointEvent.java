package com.conveyal.analysis.components.eventbus;

/**
 * Fired when the Backend is handling a single point request (forwarding it to a worker).
 */
public class SinglePointEvent extends Event {

    // This is somewhat redundant as it contains the projectId and variant index,
    // but also has a CRC since the scenario with a given index can change over time.
    public final String scenarioId;

    public final String bundleId;

    public final String regionId;

    public final int durationMsec;

    public SinglePointEvent (String scenarioId, String bundleId, String regionId, int durationMsec) {
        this.scenarioId = scenarioId;
        this.bundleId = bundleId;
        this.regionId = regionId;
        this.durationMsec = durationMsec;
    }

    @Override
    public String toString () {
        return "SinglePointEvent{" +
                "scenarioId='" + scenarioId + '\'' +
                ", regionId='" + regionId + '\'' +
                ", bundleId=" + bundleId +
                ", durationMsec=" + durationMsec +
                ", user='" + user + '\'' +
                ", accessGroup=" + accessGroup +
                ", success=" + success +
                '}';
    }
}
