package com.conveyal.r5.profile;

/**
 * This groups together all the timers recording execution time of various steps of a range raptor search.
 */
public class RaptorTimer {

    public final ExecutionTimer fullSearch = new ExecutionTimer("Full range-Raptor search");

    public final ExecutionTimer scheduledSearch = new ExecutionTimer(fullSearch, "Scheduled/bounds search");

    public final ExecutionTimer scheduledSearchTransit = new ExecutionTimer(scheduledSearch, "Scheduled search");
    public final ExecutionTimer scheduledSearchFrequencyBounds = new ExecutionTimer(scheduledSearch, "Frequency upper bounds");
    public final ExecutionTimer scheduledSearchTransfers = new ExecutionTimer(scheduledSearch, "Transfers");

    public final ExecutionTimer frequencySearch = new ExecutionTimer(fullSearch, "Frequency search");

    public final ExecutionTimer frequencySearchFrequency = new ExecutionTimer(frequencySearch, "Frequency component");
    public final ExecutionTimer frequencySearchScheduled = new ExecutionTimer(frequencySearch, "Resulting updates to scheduled component");
    public final ExecutionTimer frequencySearchTransfers = new ExecutionTimer(frequencySearch, "Transfers");

    public void log () {
        fullSearch.logWithChildren();
    }

}
