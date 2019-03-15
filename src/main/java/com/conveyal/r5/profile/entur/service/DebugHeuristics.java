package com.conveyal.r5.profile.entur.service;

import com.conveyal.r5.profile.entur.api.debug.DebugLogger;
import com.conveyal.r5.profile.entur.api.transit.TripScheduleInfo;
import com.conveyal.r5.profile.entur.rangeraptor.transit.SearchContext;
import com.conveyal.r5.profile.entur.rangeraptor.view.Heuristics;
import com.conveyal.r5.profile.entur.util.CompareIntArrays;
import com.conveyal.r5.profile.entur.util.IntUtils;

import static com.conveyal.r5.profile.entur.api.debug.DebugTopic.HEURISTICS;
import static java.util.Comparator.comparingInt;

/**
 * Utility class to log computed heuristic data.
 */
public class DebugHeuristics {
    // Any big negative number will do, but -1 is a legal value
    private static final int UNREACHED = -9999;

    private final String aName;
    private final String bName;
    private final DebugLogger logger;

    private DebugHeuristics(String aName, String bName, DebugLogger logger) {
        this.aName = aName;
        this.bName = bName;
        this.logger = logger;
    }

    public static <T extends TripScheduleInfo> void debug(
            String aName,
            Heuristics h1,
            String bName,
            Heuristics h2,
            SearchContext<T> ctx
    ) {
        if (ctx.debugLogger().isEnabled(HEURISTICS)) {
            int[] stops = IntUtils.concat(ctx.debugRequest().stops(), ctx.debugRequest().path());
            new DebugHeuristics(aName, bName, ctx.debugLogger()).debug(h1, h2, stops, ctx.request().searchForward());
        }
    }

    private void log(String message) {
        logger.debug(HEURISTICS, message);
    }

    private void debug(Heuristics fwdHeur, Heuristics revHeur, int[] stops, boolean forward) {
        log(CompareIntArrays.compare(
                "NUMBER OF TRANSFERS",
                aName, fwdHeur.bestNumOfTransfersToIntArray(UNREACHED),
                bName, revHeur.bestNumOfTransfersToIntArray(UNREACHED),
                UNREACHED,
                stops,
                comparingInt(i -> i)
        ));
        log(CompareIntArrays.compareTime(
                "TRAVEL DURATION",
                aName, fwdHeur.bestTravelDurationToIntArray(UNREACHED),
                bName, revHeur.bestTravelDurationToIntArray(UNREACHED),
                UNREACHED,
                stops,
                forward ? comparingInt(i -> i) : (l, r) -> r - l
        ));
    }
}
