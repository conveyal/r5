package com.conveyal.r5.analyst.scenario;

import com.conveyal.r5.transit.TripPattern;
import com.google.common.collect.Lists;
import gnu.trove.list.TIntList;
import gnu.trove.list.array.TIntArrayList;
import gnu.trove.set.TIntSet;
import gnu.trove.set.hash.TIntHashSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Collection;

/**
 * Skip stops and associated dwell times.
 *
 * Skipped stops are no longer served by the matched trips, and and dwell time at a skipped stop is removed from the schedule.
 * If stops are skipped at the start of a trip, the start of the trip is simply removed; the remaining times are not shifted.
 */
public class SkipStop extends TripPatternFilter {
    public static final long serialVersionUID = 1L;

    private static final Logger LOG = LoggerFactory.getLogger(SkipStop.class);

    /** Stops to skip. Note that setting this to null as a wildcard is not supported, obviously */
    public Collection<String> stopId;

    @Override
    public String getType() {
        return "skip-stop";
    }

    @Override
    public Collection<TripPattern> apply(TripPattern original) {
        return Arrays.asList(original);
    }
}
