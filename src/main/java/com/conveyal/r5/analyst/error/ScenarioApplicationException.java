package com.conveyal.r5.analyst.error;

import com.conveyal.r5.analyst.scenario.Modification;

import java.util.ArrayList;
import java.util.List;

/**
 * This Exception is thrown when a Scenario does not apply cleanly to a TransportNetwork.
 * We make an effort to recover from scenario application errors so that we can report as many errors as possible at once.
 * This Exception should contain one TaskError object for each Modification within the Scenario that failed to apply.
 */
public class ScenarioApplicationException extends RuntimeException {

    /** The structured error reports that can be sent back to the client via the broker. */
    public final List<TaskError> taskErrors = new ArrayList<>();

    /**
     * Pass in all the modifications that failed.
     * The warning messages will be extracted and they will be converted to TaskErrors.
     * @param badModifications
     */
    public ScenarioApplicationException(List<Modification> badModifications) {
        super("Errors occurred while applying a scenario to a network.");
        for (Modification modification : badModifications) {
            taskErrors.add(new TaskError(modification, modification.errors));
        }
    }

}
