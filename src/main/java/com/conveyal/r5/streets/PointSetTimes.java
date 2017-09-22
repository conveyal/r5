package com.conveyal.r5.streets;

import com.conveyal.r5.analyst.PointSet;

/**
 * The travel time to every point in a PointSet.
 * This class just serves to provide type context to a raw array of travel times, and to associate those travel times
 * with a specific PointSet to catch mistakes where a set of travel times is used with the wrong PointSet.
 *
 * This class is not really necessary.
 * Everywhere PointSetTimes is used, we always only read its travelTimes field. So this class should be deleted
 * and replaced with int[] travelTimes.
 */
public class PointSetTimes {

    public final PointSet pointSet;

    public final int[] travelTimes;

    protected PointSetTimes(PointSet pointSet, int[] travelTimes) {
        this.pointSet = pointSet;
        this.travelTimes = travelTimes;
    }

    public int size()  {
        return travelTimes.length;
    }

    public int getTravelTimeToPoint (int p) {
        return travelTimes[p];
    }
}
