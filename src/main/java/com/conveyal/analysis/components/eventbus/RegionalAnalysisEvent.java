package com.conveyal.analysis.components.eventbus;

/**
 * Represents the progress of a regional analysis over time.
 */
public class RegionalAnalysisEvent extends Event {

    public enum State {
        ENQUEUED, STARTED, COMPLETED, CANCELED, ERRORED
    }

    public final String regionalAnalysisId;

    public final State state;

    public RegionalAnalysisEvent (String regionalAnalysisId, State state) {
        this.regionalAnalysisId = regionalAnalysisId;
        this.state = state;
    }

}
