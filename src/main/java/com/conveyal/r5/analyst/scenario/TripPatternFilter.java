package com.conveyal.r5.analyst.scenario;

import com.google.common.collect.Multimap;
import com.conveyal.r5.routing.edgetype.TripPattern;
import com.conveyal.r5.routing.trippattern.TripTimes;

import java.util.Collection;

/**
 * A filter that is applied to entire trip patterns at once.
 */
public abstract class TripPatternFilter extends TimetableFilter {
    /** Apply this to a trip pattern. Be sure to make a protective copy! */
    public abstract Collection<TripPattern> apply (TripPattern original);
}
