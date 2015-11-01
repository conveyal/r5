package com.conveyal.r5.analyst.scenario;

import org.opentripplanner.routing.edgetype.TripPattern;

import java.util.Collection;

/**
 * A filter that is applied to entire trip patterns at once.
 */
public abstract class TripPatternFilter extends TimetableFilter {
    /** Apply this to a trip pattern. Be sure to make a protective copy! */
    public abstract Collection<TripPattern> apply (TripPattern original);
}
