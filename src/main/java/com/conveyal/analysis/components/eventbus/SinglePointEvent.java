package com.conveyal.analysis.components.eventbus;

import com.fasterxml.jackson.annotation.JsonTypeName;

/**
 * Created by abyrd on 2020-06-12
 */
@JsonTypeName("singpo")
public class SinglePointEvent extends Event {

    public final String scenarioId;

    public final int durationMsec;

    public SinglePointEvent (String scenarioId, int durationMsec) {
        this.scenarioId = scenarioId;
        this.durationMsec = durationMsec;
    }

}
