package com.conveyal.r5.transit;

import com.conveyal.r5.api.util.TransitModes;
import com.conveyal.r5.util.Tuple2;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.EnumSet;
import java.util.List;

import static com.conveyal.r5.transit.TransitLayer.getTransitModes;

/**
 * Holds all the FilteredPatterns instances for a particular TransitLayer (scenario) given a particular set of
 * filtering criteria (transit modes and active services). There is one FilteredPattern instance for each TripPattern
 * that is present in the filtered TransitLayer. Many TripPatterns contain a mixture of trips from different days,
 * and those trips appear to overtake one another if we do not filter them down. Filtering allows us to flag more
 * effectively which patterns have no overtaking, which is useful because departure time searches can be then optimized
 * for patterns with no overtaking. All trips in a TripPattern are defined to be on same route, and GTFS allows only one
 * mode per route.
 */
public class FilteredPatterns {

    /**
     * List with the same length and indexes as the unfiltered TripPatterns in the input TransitLayer.
     * Patterns that do not meet the mode/services filtering criteria are recorded as null.
     */
    public final List<FilteredPattern> patterns;

    /** The indexes of the trip patterns running on a given day with frequency-based trips of selected modes. */
    public BitSet runningFrequencyPatterns = new BitSet();

    /** The indexes of the trip patterns running on a given day with scheduled trips of selected modes. */
    public BitSet runningScheduledPatterns = new BitSet();

    /**
     * Construct FilteredPatterns from the given TransitLayer, filtering for the specified modes and active services.
     * It's tempting to use List.of() or Collectors.toUnmodifiableList() but these cause an additional array copy.
     */
    public FilteredPatterns (TransitLayer transitLayer, EnumSet<TransitModes> modes, BitSet services) {
        List<TripPattern> sourcePatterns = transitLayer.tripPatterns;
        patterns = new ArrayList<>(sourcePatterns.size());
        for (int patternIndex = 0; patternIndex < sourcePatterns.size(); patternIndex++) {
            TripPattern pattern = sourcePatterns.get(patternIndex);
            RouteInfo routeInfo = transitLayer.routes.get(pattern.routeIndex);
            TransitModes mode = getTransitModes(routeInfo.route_type);
            if (pattern.servicesActive.intersects(services) && modes.contains(mode)) {
                patterns.add(new FilteredPattern(pattern, services));
                // At least one trip on this pattern is relevant, based on the profile request's date and modes.
                if (pattern.hasFrequencies) {
                    runningFrequencyPatterns.set(patternIndex);
                }
                // Schedule case is not an "else" clause because we support patterns with both frequency and schedule.
                if (pattern.hasSchedules) {
                    runningScheduledPatterns.set(patternIndex);
                }
            } else {
                patterns.add(null);
            }
        }
    }

}
