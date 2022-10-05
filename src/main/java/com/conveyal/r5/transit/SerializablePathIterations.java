package com.conveyal.r5.transit;

import java.util.Collection;
import java.util.stream.Collectors;

/**
 * Wraps path and iteration details for JSON serialization
 */
public class SerializablePathIterations {
    public String access; // StreetTimesAndModes.StreetTimeAndMode would be more machine-readable.
    public String egress;
    public Collection<RouteSequence.TransitLeg> transitLegs;
    public Collection<HumanReadableTemporalIterationDetails> iterations;

    public SerializablePathIterations(RouteSequence pathTemplate, TransitLayer transitLayer, Collection<IterationTemporalDetails> iterations) {
        this.access = pathTemplate.stopSequence.access == null ? null : pathTemplate.stopSequence.access.toString();
        this.egress = pathTemplate.stopSequence.egress == null ? null : pathTemplate.stopSequence.egress.toString();
        this.transitLegs = pathTemplate.transitLegs(transitLayer);
        this.iterations = iterations.stream().map(HumanReadableTemporalIterationDetails::new).collect(Collectors.toList());
        iterations.forEach(pathTemplate.stopSequence::transferTime); // The transferTime method includes an
        // assertion that the transfer time is non-negative, i.e. that the access + egress + wait + ride times of
        // a specific iteration do not exceed the total travel time. Perform that sense check here, even though
        // the transfer time is not reported to the front-end for the human-readable single-point responses.
        // TODO add transferTime to HumanReadableIteration?
    }
}
