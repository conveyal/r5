package com.conveyal.r5.streets;

import com.conveyal.r5.profile.ProfileRequest;
import com.conveyal.r5.profile.StreetMode;

/// Wraps another TraversalTimeCalculator, scaling every time it produces by a constant factor.
/// This is used for on-demand rides (often derived from GTFS-Flex feeds), where the duration of
/// rides is subject to a linear transform (factor * direct_duration + offset). If the factor were
/// applied to result states, the pre-ride walk would also be scaled.
///
/// Unlike MultistageTraversalTimeCalculator, turn costs are also scaled, following the description
/// of safe_duration_factor from the GTFS-Flex specification.
///
/// The base calculator already rounds up to whole seconds. This wrapper rounds that ceilinged
/// value, keeping the outer rounding zero-mean. A ceiling here would bias upward and accumulate
/// across many edges. Therefore, the scaled total does not exactly equal factor * unscaled_total,
/// but should be accurate to within a few seconds.
public class OnDemandTraversalTimeCalculator implements TraversalTimeCalculator {

    private final TraversalTimeCalculator base;

    private final double factor;

    public OnDemandTraversalTimeCalculator (TraversalTimeCalculator base, double factor) {
        this.base = base;
        this.factor = factor;
    }

    @Override
    public int traversalTimeSeconds (EdgeStore.Edge currentEdge, StreetMode streetMode, ProfileRequest req) {
        return (int) Math.round(base.traversalTimeSeconds(currentEdge, streetMode, req) * factor);
    }

    @Override
    public int turnTimeSeconds (int fromEdge, int toEdge, StreetMode streetMode) {
        return (int) Math.round(base.turnTimeSeconds(fromEdge, toEdge, streetMode) * factor);
    }

}
