package com.conveyal.r5.profile.entur;

import com.conveyal.r5.profile.entur.api.debug.DebugLogger;
import com.conveyal.r5.profile.entur.api.transit.TripScheduleInfo;
import com.conveyal.r5.profile.entur.rangeraptor.transit.SearchContext;
import com.conveyal.r5.profile.entur.rangeraptor.view.Heuristics;
import com.conveyal.r5.profile.entur.util.CompareIntArrays;
import com.conveyal.r5.profile.entur.util.IntUtils;

import static com.conveyal.r5.profile.entur.api.debug.DebugTopic.HEURISTICS;

/**
 * Utility class to log computed heuristic data.
 */
class DebugHeuristics {
    private static final int UNREACHED = -1;

    /** Protect from instantiation */
    private DebugHeuristics() { }

    public static <T extends TripScheduleInfo> void debug(Heuristics h1, Heuristics h2, SearchContext<T> ctx) {
        if (ctx.debugLogger().isEnabled(HEURISTICS)) {
            int[] stops = IntUtils.concat(ctx.debugRequest().stops(), ctx.debugRequest().path());
            DebugLogger logger = ctx.debugLogger();
            logger.debug(HEURISTICS, debugNumberOfTransfers(h1, h2, stops));
            logger.debug(HEURISTICS, debugTravelDuration(h1, h2, stops));
        }
    }

    private static String debugNumberOfTransfers(Heuristics fwdHeur, Heuristics revHeur, int[] stops) {
        return CompareIntArrays.compare(
                "Num of Transfers",
                "Forward", fwdHeur.bestNumOfTransfersToIntArray(UNREACHED),
                "Backward", revHeur.bestNumOfTransfersToIntArray(UNREACHED),
                UNREACHED,
                stops
        );
    }

    private static String debugTravelDuration(Heuristics fwdHeur, Heuristics revHeur, int[] stops) {
        return CompareIntArrays.compareTime(
                "Travel duration",
                "Forward", fwdHeur.bestTravelDurationToIntArray(UNREACHED),
                "Backward", revHeur.bestTravelDurationToIntArray(UNREACHED),
                UNREACHED,
                stops

        );
    }
}
