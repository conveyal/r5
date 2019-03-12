package com.conveyal.r5.profile.entur.api.debug;

public enum DebugTopic {
    /**
     * Log computed heuristic information for stops listed in the debug request stops and trip list.
     * If not stops are specified some of the first stops are listed.
     */
    HEURISTICS,

    /**
     * Log search statistics, like number of stops visited and so on.
     */
    SEARCH_STATS
}
