package com.conveyal.r5.streets;

import static com.conveyal.r5.streets.LaDotCostTags.BikeInfrastructure.BOULEVARD;
import static com.conveyal.r5.streets.LaDotCostTags.BikeInfrastructure.PATH;
import static com.conveyal.r5.streets.LaDotCostTags.ControlType.SIGNAL;
import static com.conveyal.r5.streets.LaDotCostTags.ControlType.STOP;

public class LaDotBikeCostSupplier implements SingleModeTraversalTimes.Supplier {

    private static final double STANDARD_BIKE_SPEED_M_PER_SEC = 4;

    private final LaDotCostTags tags;

    public LaDotBikeCostSupplier (LaDotCostTags tags) {
        this.tags = tags;
    }

    @Override
    public double perceivedLengthMultipler () {
        // Original formula:
        // distance + distance * (bike_blvd_penalty + bike_path_penalty + slope_penalty + no_bike_penalty)
        // Start at unity
        double factor = 1;
        // Reduce cost if bike infrastructure is present.
        // TODO define constants for all bonuses and costs
        if (tags.bikeInfrastructure == BOULEVARD) {
            factor -= 0.108;
        } else if (tags.bikeInfrastructure == PATH) {
            factor -= 0.16;
        } else {
            // Where bike infrastructure is not present, increase cost depending on traffic level.
            // TODO interpolate penalty to de-bin AADT?
            int aadt = tags.selfAADT;
            if (aadt > 30_000) {
                factor += 7.157;
            } else if (aadt > 20_000) {
                factor += 1.4;
            } else if (aadt > 10_000) {
                factor += 0.368;
            }
        }
        // The penalty constant is for a road that is 100% in that slope bin.
        // Percentages are given as fractional values in [0...1] with some slightly exceeding 1 due to rounding errors.
        factor += tags.slopePercent2to4 * 0.371;
        factor += tags.slopePercent4to6 * 1.23;
        factor += tags.slopePercent6plus * 3.239;
        return factor;
    }

    @Override
    public int turnTimeSeconds (SingleModeTraversalTimes.TurnDirection turnDirection) {
        return (int)(computeBikeTurnCostM(turnDirection) / STANDARD_BIKE_SPEED_M_PER_SEC);
    }

    /** @return the cost in effective meters of turning off the given edge in the given direction on a bicycle. */
    private int computeBikeTurnCostM (SingleModeTraversalTimes.TurnDirection turnDirection) {
        int penaltyMeters = 0;
        // Stop signs and traffic lights add a penalty to all directions.
        if (tags.controlType == STOP) {
            penaltyMeters += 6;
        } else if (tags.controlType == SIGNAL) {
            penaltyMeters += 27;
        }
        // A fixed penalty (turn_penalty) is added for left and right turns (not straight through).
        if (turnDirection != SingleModeTraversalTimes.TurnDirection.STRAIGHT) {
            penaltyMeters += 54;
        }
        // Cross traffic affects all directions, but right is different than left and straight.
        int crossAADT = tags.crossAADT;
        if (turnDirection == SingleModeTraversalTimes.TurnDirection.RIGHT) {
            // cross_traffic_penalty_r
            if (crossAADT > 10_000) {
                penaltyMeters += 50;
            }
        } else {
            // cross_traffic_penalty_ls
            if (crossAADT > 20_000) {
                penaltyMeters += 424;
            } else if (crossAADT > 10_000) {
                penaltyMeters += 81;
            } else if (crossAADT > 5_000) {
                penaltyMeters += 78;
            }
        }
        // Parallel traffic affects left turns (parallel_traffic_penalty)
        if (turnDirection == SingleModeTraversalTimes.TurnDirection.LEFT) {
            if (tags.parallelAADT > 20_000) {
                penaltyMeters += 297;
            } else if (tags.parallelAADT > 10_000) {
                penaltyMeters += 117;
            }
        }
        return penaltyMeters;
    }

}
