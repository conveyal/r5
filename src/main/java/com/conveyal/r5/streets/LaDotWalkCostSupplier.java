package com.conveyal.r5.streets;

public class LaDotWalkCostSupplier implements SingleModeTraversalTimes.Supplier {

    private static final double STANDARD_WALK_SPEED_M_PER_SEC = 1.3;

    private final LaDotCostTags tags;

    public LaDotWalkCostSupplier (LaDotCostTags tags) {
        this.tags = tags;
    }

    @Override
    public double perceivedLengthMultipler () {
        // Original formula: distance + distance * (slope_penalty + unpaved_alley_penalty + busy_penalty + nbd_penalty)
        // Start at unity
        double factor = 1;
        factor += tags.slopePercent10plus * 0.99;
        if (tags.isUnpavedOrAlley) {
            factor += 0.51;
        }
        if (tags.isBusyRoad) {
            factor += 0.14;
        }
        return factor;
    }

    @Override
    public int turnTimeSeconds (SingleModeTraversalTimes.TurnDirection turnDirection) {
        return (int)(computeWalkTurnCostM(turnDirection) / STANDARD_WALK_SPEED_M_PER_SEC);
    }

    /** @return the cost in effective meters of turning off the given edge in the given direction while walking. */
    private int computeWalkTurnCostM (SingleModeTraversalTimes.TurnDirection turnDirection) {

        // All directions incur a fixed cost for crossing an intersection (turn_penalty)
        int penaltyMeters = 54;

        // Busy roads without (signalized) crosswalks incur a penalty.
        double dailyTraffic;
        if (turnDirection == SingleModeTraversalTimes.TurnDirection.STRAIGHT) {
            // When passing straight through, categorize the intersection by cross traffic.
            dailyTraffic = tags.crossAADT;
        } else {
            // For left and right turns, categorize the traffic on the road itself (not the crossroad).
            dailyTraffic = Math.max(tags.selfAADT, tags.parallelAADT);
        }
        if (dailyTraffic >= 13_000) {
            // Busy arterial street. Penalty if it does not have a signalized crosswalk.
            // In docs this has an upper bound of 23_000, is that correct?
            if (tags.crosswalkType != LaDotCostTags.CrosswalkType.SIGNALIZED) {
                penaltyMeters += 73;
            }
        } else if (dailyTraffic >= 10_000) {
            // Less busy collector street. Penalty if it has no crosswalk at all.
            if (tags.crosswalkType == LaDotCostTags.CrosswalkType.NONE) {
                penaltyMeters += 28;
            }
        }
        return penaltyMeters;
    }

}
