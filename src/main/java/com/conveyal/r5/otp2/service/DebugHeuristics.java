package com.conveyal.r5.otp2.service;

import com.conveyal.r5.otp2.api.debug.DebugLogger;
import com.conveyal.r5.otp2.api.request.DebugRequest;
import com.conveyal.r5.otp2.api.request.RangeRaptorRequest;
import com.conveyal.r5.otp2.api.view.Heuristics;
import com.conveyal.r5.otp2.util.CompareIntArrays;
import com.conveyal.r5.otp2.util.IntUtils;

import static com.conveyal.r5.otp2.api.debug.DebugTopic.HEURISTICS;
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
    private final int[] stops;

    private DebugHeuristics(String aName, String bName, DebugRequest<?> debugRequest) {
        this.aName = aName;
        this.bName = bName;
        this.logger = debugRequest.logger();
        this.stops = IntUtils.concat(debugRequest.stops(), debugRequest.path());
    }

    public static void debug(
            String aName,
            Heuristics h1,
            String bName,
            Heuristics h2,
            RangeRaptorRequest<?> request
    ) {
        DebugRequest<?> debug = request.debug();
        if (debug.logger().isEnabled(HEURISTICS)) {
            new DebugHeuristics(aName, bName, debug).debug(h1, h2, request.searchForward());
        }
    }

    private void log(String message) {
        logger.debug(HEURISTICS, message);
    }

    private void debug(Heuristics fwdHeur, Heuristics revHeur, boolean forward) {
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
