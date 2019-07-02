package com.conveyal.r5.streets;

import com.conveyal.r5.analyst.PointSet;
import com.conveyal.r5.profile.FastRaptorWorker;

import java.util.Arrays;

import static com.conveyal.r5.profile.FastRaptorWorker.UNREACHED;

/**
 * The travel time to every point in a PointSet.
 *
 * This class is not really necessary.
 * Everywhere PointSetTimes is used, we always only read its travelTimes field. So this class should be deleted
 * and replaced with int[] travelTimes.
 * However it does serve to provide type context to a raw array of travel times, and to associate those travel times
 * with a specific PointSet to catch mistakes where a set of travel times is used with the wrong PointSet.
 */
public class PointSetTimes {

    public final PointSet pointSet;

    public final int[] travelTimes;

    protected PointSetTimes(PointSet pointSet, int[] travelTimes) {
        this.pointSet = pointSet;
        this.travelTimes = travelTimes;
    }

    public static PointSetTimes allUnreached (PointSet pointSet) {
        int[] times = new int[pointSet.featureCount()];
        Arrays.fill(times, FastRaptorWorker.UNREACHED);
        return new PointSetTimes(pointSet, times);
    }

    public int size()  {
        return travelTimes.length;
    }

    public int getTravelTimeToPoint (int p) {
        return travelTimes[p];
    }

    /**
     * Increment all reachable points by the given number of seconds.
     */
    public void incrementAllReachable(int seconds) {
        for (int i = 0; i < travelTimes.length; i++) {
            if (travelTimes[i] != UNREACHED) {
                travelTimes[i] += seconds;
            }
        }
    }

    /**
     * Merge the two PointSetTimes, returning a new PointSetTimes containing the minimum value at each point.
     * The first operand may be null, which allows iteratively accumulating into an uninitialized PointSet variable.
     */
    public static PointSetTimes minMerge (PointSetTimes a, PointSetTimes b) {
        if (b == null) {
            throw new UnsupportedOperationException("Second operand may not be null.");
        }
        if (a == null) {
            return b;
        }
        if (a.pointSet != b.pointSet) {
            throw new UnsupportedOperationException("Both PointSetTimes must be for the same PointSet.");
        }
        if (a.travelTimes.length != b.travelTimes.length) {
            throw new UnsupportedOperationException("Both PointSetTimes must have the same number of times.");
        }
        int[] travelTimes = new int[b.travelTimes.length];
        for (int i = 0; i < travelTimes.length; i++) {
            travelTimes[i] = Math.min(a.travelTimes[i], b.travelTimes[i]);
        }
        return new PointSetTimes(b.pointSet, travelTimes);
    }


}
