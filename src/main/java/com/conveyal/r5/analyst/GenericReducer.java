package com.conveyal.r5.analyst;

import com.beust.jcommander.ParameterException;
import com.conveyal.r5.OneOriginResult;
import com.conveyal.r5.analyst.cluster.AnalysisTask;
import com.conveyal.r5.analyst.cluster.Origin;
import com.conveyal.r5.analyst.cluster.RegionalTask;
import com.conveyal.r5.analyst.cluster.TimeGrid;
import com.conveyal.r5.analyst.cluster.TravelTimeSurfaceTask;
import com.conveyal.r5.profile.FastRaptorWorker;
import com.conveyal.r5.profile.PerTargetPropagater;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;

/**
 * Merge the two existing reducers.
 */
public class GenericReducer implements PerTargetPropagater.TravelTimeReducer {

    private static final Logger LOG = LoggerFactory.getLogger(GenericReducer.class);

    /** The task used to create travel times being reduced herein. */
    public final AnalysisTask task;

    /** Travel time results for a whole grid of destinations. May be null if we're only recording accessibility. */
    private TimeGrid timeGrid = null;

    private AccessibilityResult accessibilityResult = null;

    private final boolean retainTravelTimes;

    private final boolean calculateAccessibility;

    private final int[] percentileIndexes;

    private final int nPercentiles;

    private final int timesPerDestination;

    public GenericReducer(AnalysisTask task) {

        this.task = task;
        this.timesPerDestination = task.getMonteCarloDrawsPerMinute() * task.getTimeWindowLengthMinutes();
        this.nPercentiles = task.percentiles.length;
        this.percentileIndexes = new int[nPercentiles];
        for (int p = 0; p < nPercentiles; p++) {
            percentileIndexes[p] = findPercentileIndex(timesPerDestination, task.percentiles[p]);
        }

        // Decide whether we want to retain travel times to all destinations for this origin.
        retainTravelTimes = task instanceof TravelTimeSurfaceTask || task.makeStaticSite;
        if (retainTravelTimes) {
            timeGrid = new TimeGrid(task.zoom, task.west, task.north, task.width, task.height, task.percentiles.length);
        }

        // Decide whether we want to calculate cumulative opportunities accessibility indicators for this origin.
        calculateAccessibility = task instanceof RegionalTask && ((RegionalTask)task).gridData != null;
        if (calculateAccessibility) {
            accessibilityResult = new AccessibilityResult(
                new Grid[] {((RegionalTask)task).gridData},
                new int[]{task.maxTripDurationMinutes},
                task.percentiles
            );
        }
    }


    /**
     * Compute the index into a sorted list of N elements at which a particular percentile will be found.
     * Our method does not interpolate, it always reports a value actually appearing in the list of elements.
     * That is to say, the percentile will be found at an integer-valued index into the sorted array of elements.
     * Definition of non-interpolated percentile: the smallest value in the list such that no more than P percent of
     * the data is strictly less than the value and at least P percent of the data is less than or equal to that value.
     * The 100th percentile is defined as the largest value in the list.
     * See https://en.wikipedia.org/wiki/Percentile#Definitions
     */
    // We scale the interval between the beginning and end elements of the array (the min and max values).
    // In an array with N values this interval is N-1 elements. We should be scaling N-1, which makes the result
    // always defined even when using a high percentile and low number of elements.  Previously, this caused
    // an error below when requesting the 95th percentile when times.length = 1 (or any length less than 10).
    // Int divide will floor; this is correct because value 0 has travel times of up to one minute, etc.
    // This means that anything less than a cutoff of (say) 60 minutes (in seconds) will have value 59,
    // which is what we want. But maybe converting to minutes before we actually export a binary format is tying
    // the backend and frontend (which makes use of UInt8 typed arrays) too closely.
    private static int findPercentileIndex(int nElements, double percentile) {
        // FIXME this should be floor not round. The definition uses ceiling for one-based indexes and we have zero-based indexes.
        return (int) Math.round(percentile / 100 * nElements);
    }

    @Override
    public void recordTravelTimesForTarget (int target, int[] times) {
        // Sort the times at each target and read off percentiles at the pre-calculated indexes.
        int[] percentileTravelTimesMinutes = new int[nPercentiles];
        if (times.length == 1) {
            // Handle results with no variation
            // TODO instead of conditionals maybe overload this function to have one version that takes a single int time.
            Arrays.fill(percentileTravelTimesMinutes, times[0]);
        } else if (times.length == timesPerDestination) {
            Arrays.sort(times);
            for (int p = 0; p < nPercentiles; p++) {
                int timeSeconds = times[percentileIndexes[p]];
                if (timeSeconds == FastRaptorWorker.UNREACHED) {
                    percentileTravelTimesMinutes[p] = FastRaptorWorker.UNREACHED;
                } else {
                    int timeMinutes = timeSeconds / 60;
                    percentileTravelTimesMinutes[p] = timeMinutes;
                }
            }
        } else {
            throw new ParameterException("Must supply expected number of times or only one time.");
        }

        if (retainTravelTimes) {
            timeGrid.setTarget(target, percentileTravelTimesMinutes);
        }
        if (calculateAccessibility) {
            // This x/y addressing can only work with one grid at a time,
            // needs to be made absolute to handle multiple different extents.
            Grid grid = accessibilityResult.grids[0];
            int x = target % grid.width;
            int y = target / grid.width;
            double amount = grid.grid[x][y];
            for (int p = 0; p < nPercentiles; p++) {
                if (percentileTravelTimesMinutes[p] < task.maxTripDurationMinutes) { // TODO less than or equal?
                    accessibilityResult.incrementAccessibility(0, 0, p, amount);
                }
            }
        }
    }

    /**
     * If no travel times to destinations have been streamed in by calling recordTravelTimesForTarget, the
     * TimeGrid will have a buffer full of UNREACHED. This allows shortcutting around
     * routing and propagation when the origin point is not connected to the street network.
     */
    @Override
    public OneOriginResult finish () {
        Origin origin = null;
        if (calculateAccessibility) {
            origin = accessibilityResult.makeOrigin((RegionalTask)task);
        }
        return new OneOriginResult(task, timeGrid, origin);
    }

}
