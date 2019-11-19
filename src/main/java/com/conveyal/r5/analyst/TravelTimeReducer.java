package com.conveyal.r5.analyst;

import com.beust.jcommander.ParameterException;
import com.conveyal.r5.OneOriginResult;
import com.conveyal.r5.analyst.cluster.AnalysisTask;
import com.conveyal.r5.analyst.cluster.Origin;
import com.conveyal.r5.analyst.cluster.RegionalTask;
import com.conveyal.r5.analyst.cluster.TimeGrid;
import com.conveyal.r5.analyst.cluster.TravelTimeSurfaceTask;
import com.conveyal.r5.profile.FastRaptorWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;

import static com.conveyal.r5.profile.FastRaptorWorker.UNREACHED;

/**
 * Given a bunch of travel times from an origin to a single destination grid cell, this collapses that long list into a
 * limited number of percentiles, then optionally accumulates that destination's opportunity count into the appropriate
 * cumulative opportunities accessibility indicators at that origin.
 */
public class TravelTimeReducer {

    private static final Logger LOG = LoggerFactory.getLogger(TravelTimeReducer.class);

    /**
     * Maximum total travel time, above which a destination should be considered unreachable. Note the logic in
     * analysis-backend AnalysisRequest, which sets this to the requested value for regional analyses, but keeps
     * it at the default value from R5 ProfileRequest for single-point requests (which allow adjusting the cutoff
     * after results have been calculated)
     */
    private final int maxTripDurationMinutes;

    /** Travel time results for a whole grid of destinations. May be null if we're only recording accessibility. */
    private final TimeGrid timeGrid;

    private final AccessibilityResult accessibilityResult;

    private final boolean retainTravelTimes;

    private final boolean calculateAccessibility;

    private final int[] percentileIndexes;

    private final int nPercentiles;

    /**
     * The number of travel times we will record at each destination.
     * This is affected by the number of Monte Carlo draws requested and the departure time window.
     */
    private final int timesPerDestination;

    /**
     * @param task task to be performed, which is used to determine how results are summarized at each origin: a single
     *             cumulative opportunity accessibility value per origin, or selected percentiles of travel times to
     *             all destinations.
     *
     *             The task is also used to determine the number of timesPerDestination, which depends on whether the
     *             task specifies an inRoutingFareCalculator. A non-null inRoutingFareCalculator is used as a flag
     *             for the multi-criteria McRaptor router, which is relatively slow, so it relies on sampling (using
     *             a number of departure times specified by task.monteCarloDraws). FastRaptorworker is fast enough to
     *             run Monte Carlo draws within departure minutes, so it uses the monteCarloDraws parameter in a way
     *             that's consistent with its name.
     */
    public TravelTimeReducer (AnalysisTask task) {

        this.maxTripDurationMinutes = task.maxTripDurationMinutes;

        // Set timesPerDestination depending on how waiting time/travel time variability will be sampled
        if (task.inRoutingFareCalculator != null) {
            // Calculating fares within routing (using the McRaptor router) is slow, so sample at different departure
            // times (rather than sampling multiple draws per minute).
            this.timesPerDestination = task.monteCarloDraws;
        } else {
            if (task.monteCarloDraws == 0) {
                // Use HALF_HEADWAY boarding assumption, which returns a single travel time per departure minute per
                // destination.
                this.timesPerDestination = task.getTimeWindowLengthMinutes();
            } else {
                this.timesPerDestination = task.getTimeWindowLengthMinutes() * task.getMonteCarloDrawsPerMinute();
            }
        }

        this.nPercentiles = task.percentiles.length;

        // We pre-compute the indexes at which we'll find each percentile in a sorted list of the given length.
        this.percentileIndexes = new int[nPercentiles];
        for (int p = 0; p < nPercentiles; p++) {
            percentileIndexes[p] = findPercentileIndex(timesPerDestination, task.percentiles[p]);
        }

        // Decide whether we want to retain travel times to all destinations for this origin.
        retainTravelTimes = task instanceof TravelTimeSurfaceTask || task.makeStaticSite;
        if (retainTravelTimes) {
            timeGrid = new TimeGrid(task.zoom, task.west, task.north, task.width, task.height, task.percentiles.length);
        } else {
            timeGrid = null;
        }

        // Decide whether we want to calculate cumulative opportunities accessibility indicators for this origin.
        calculateAccessibility = task instanceof RegionalTask && ((RegionalTask)task).gridData != null;
        if (calculateAccessibility) {
            accessibilityResult = new AccessibilityResult(
                new Grid[] {((RegionalTask)task).gridData},
                new int[]{task.maxTripDurationMinutes},
                task.percentiles
            );
        } else {
            accessibilityResult = null;
        }
    }


    /**
     * Compute the index into a sorted list of N elements at which a particular percentile will be found.
     * Our method does not interpolate, it always reports a value actually appearing in the list of elements.
     * That is to say, the percentile will be found at an integer-valued index into the sorted array of elements.
     * The definition of a non-interpolated percentile is as follows: the smallest value in the list such that no more
     * than P percent of the data is strictly less than the value and at least P percent of the data is less than or
     * equal to that value. By this definition, the 100th percentile is the largest value in the list.
     * See https://en.wikipedia.org/wiki/Percentile#Definitions
     *
     * The formula given on Wikipedia next to definition cited above uses ceiling for one-based indexes.
     * It is tempting to just truncate to ints instead of ceiling but this gives different results on integer boundaries.
     */
    private static int findPercentileIndex(int nElements, double percentile) {
        return (int)(Math.ceil(percentile / 100 * nElements) - 1);
    }

    /**
     * Given a list of travel times of the expected length, extract the requested percentiles. Either the extracted
     * percentiles or the resulting accessibility values (or both) are then stored.
     * WARNING: this method destructively sorts the supplied times in place.
     * Their positions in the array will no longer correspond to the raptor iterations that produced them.
     * @param timesSeconds from which we will extract percentiles.
     * @return the extracted travel times, in minutes. This is a hack to enable scoring paths in the caller.
     */
    public int[] recordTravelTimesForTarget (int target, int[] timesSeconds) {
        // TODO factor out getPercentiles method for clarity
        // Sort the times at each target and read off percentiles at the pre-calculated indexes.
        int[] percentileTravelTimesMinutes = new int[nPercentiles];
        if (timesSeconds.length == 1) {
            // Handle results with no variation, e.g. from walking, biking, or driving.
            // TODO instead of conditionals maybe overload this function to have one version that takes a single int time and wraps this array function.
            int travelTimeSeconds = timesSeconds[0];
            Arrays.fill(percentileTravelTimesMinutes, convertToMinutes(travelTimeSeconds));
        } else if (timesSeconds.length == timesPerDestination) {
            // Instead of a general purpose sort we perform a counting sort on the times. This is efficient because
            // the number of possible values (120) can be much smaller than the number of iterations, and should easily
            // fit in very fast processor cache. This performs a lot of division on numbers that we don't actually use.
            // But in profiling it was slower to have a larger array indexed in seconds and only convert the extracted
            // percentiles to minutes. Pre-division does keep the array very small. The counting sort is only partially
            // completed, then we read off the percentiles without reordering the original values.
            // This array tracks the number of times each travel duration (in minutes) appears in the input array.
            // Anything 120 minutes and up (including UNREACHED) is mapped to 120, then 120 mapped back to UNREACHED.
            int[] counts = new int[121];
            for (int timeSeconds : timesSeconds) {
                int timeMinutes = convertToMinutes(timeSeconds);
                if (timeMinutes >= 120) {
                    timeMinutes = 120;
                }
                counts[timeMinutes] += 1;
            }
            // NOTE: These percentile indexes must be in increasing order,
            // which means task.percentiles must be in ascending order.
            // TODO state and check this prerequisite somewhere outside this tight inner loop.
            int minute = 0;
            int cumulativeCount = counts[0];
            for (int pi = 0; pi < percentileIndexes.length; pi++) {
                int percentileIndex = percentileIndexes[pi];
                while (cumulativeCount <= percentileIndex) {
                    minute += 1;
                    cumulativeCount += counts[minute];
                }
                if (minute == 120) {
                    minute = UNREACHED;
                }
                percentileTravelTimesMinutes[pi] = minute;
            }
        } else {
            throw new ParameterException("You must supply the expected number of travel time values (or only one value).");
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
                // Use of < here (as opposed to <=) matches the definition in JS front end,
                // and works well when truncating seconds to minutes.
                if (percentileTravelTimesMinutes[p] < maxTripDurationMinutes) {
                    accessibilityResult.incrementAccessibility(0, 0, p, amount);
                }
            }
        }
        return percentileTravelTimesMinutes;
    }

    /**
     * Convert the given timeSeconds to minutes. If that time equals or exceeds the maxTripDurationMinutes, instead
     * return a value indicating that the location is unreachable. The minutes to seconds conversion uses integer
     * division, which truncates toward zero. This approach is correct for use in accessibility analysis, where we
     * are always testing whether a travel time is less than a certain threshold value. For example, all travel times
     * between 59 and 60 minutes will truncate to 59, and will correctly return true for the expression (t < 60
     * minutes). We are converting seconds to minutes before we export a binary format mainly to narrow the times so
     * they fit into single bytes (though this also reduces entropy and makes compression more effective). Arguably
     * this is coupling the backend too closely to the frontend (which makes use of UInt8 typed arrays); the front
     * end could in principle receive a more general purpose format using wider or variable width integers.
     */
    private int convertToMinutes (int timeSeconds) {
        if (timeSeconds == UNREACHED) return UNREACHED;
        int timeMinutes = timeSeconds / FastRaptorWorker.SECONDS_PER_MINUTE;
        if (timeMinutes < maxTripDurationMinutes) {
            return timeMinutes;
        } else {
            return UNREACHED;
        }
    }


    /**
     * If no travel times to destinations have been streamed in by calling recordTravelTimesForTarget, the
     * TimeGrid will have a buffer full of UNREACHED. This allows shortcutting around
     * routing and propagation when the origin point is not connected to the street network.
     */
    public OneOriginResult finish () {
        return new OneOriginResult(timeGrid, accessibilityResult);
    }

}
