package com.conveyal.r5.elevation;

import com.conveyal.r5.streets.EdgeStore;
import org.apache.commons.math3.util.FastMath;

/**
 * Created by abyrd on 2021-07-20
 */
public class ToblerCalculator implements EdgeStore.ElevationSegmentConsumer {

    public static final double DECIMETERS_PER_METER = 10;

    private double weightedToblerSum = 0;
    private double xDistanceConsumed = 0;

    @Override
    public void consumeElevationSegment (int index, double xMeters, double yMeters) {
        weightedToblerSum += xMeters * tobler(xMeters, yMeters);
        xDistanceConsumed += xMeters;
    }

    public double getDistanceConsumed () {
        return xDistanceConsumed;
    }

    public double weightedToblerAverage() {
        return weightedToblerSum / xDistanceConsumed;
    }

    /**
     * Tobler's hiking function, normalized to 1 rather than 6 m/sec so results can scale user-specified speeds.
     * Elevation points are evenly spaced. We can store average normalized speeds over the linestring for an edge.
     * See: https://en.wikipedia.org/wiki/Tobler%27s_hiking_function
     * Also: https://wildfiretoday.com/documents/Slope_travel_rates.pdf
     */
    public static double tobler (double dx, double dy) {
        return FastMath.exp(-3.5 * FastMath.abs((dy/dx) + 0.05));
    }

    public static double weightedAverageForEdge (EdgeStore.Edge edge) {
        ToblerCalculator calculator = new ToblerCalculator();
        edge.forEachElevationSegment(calculator);
        return calculator.weightedToblerAverage();
    }

}
