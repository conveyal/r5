package com.conveyal.r5.analyst.error;

import com.conveyal.r5.analyst.scenario.Modification;
import com.sun.org.apache.xpath.internal.operations.Mod;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Created by abyrd on 2016-12-31
 */
public class ScenarioApplicationException extends RuntimeException {

    // The structured error reports that can be sent back to the client via the broker.
    public final List<TaskError> taskErrors = new ArrayList<>();

    /**
     * Pass in all the modifications that
     * @param badModifications
     */
    public ScenarioApplicationException(List<Modification> badModifications) {
        super("Errors occurred while applying a scenario to a network.");
        for (Modification modification : badModifications) {
            taskErrors.add(new TaskError(modification));
        }
    }

}
