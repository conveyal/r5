package com.conveyal.r5.analyst;

import com.conveyal.r5.OneOriginResult;
import com.conveyal.r5.analyst.cluster.AnalysisWorkerTask;
import com.conveyal.r5.analyst.cluster.PathResult;
import com.conveyal.r5.analyst.cluster.RegionalTask;
import com.conveyal.r5.analyst.cluster.TravelTimeResult;
import com.conveyal.r5.analyst.cluster.TravelTimeSurfaceTask;
import com.conveyal.r5.analyst.decay.DecayFunction;
import com.conveyal.r5.profile.FastRaptorWorker;
import com.conveyal.r5.profile.Path;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;

import static com.conveyal.r5.common.Util.notNullOrEmpty;
import static com.conveyal.r5.profile.FastRaptorWorker.UNREACHED;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;

/**
 * Given a bunch of travel times from an origin to a single destination point, this collapses that long list into a
 * limited number of percentiles, then optionally accumulates that destination's opportunity count into the
 * appropriate cumulative opportunities accessibility indicators at that origin.
 */
public class TravelTimeReducer {

    private static final Logger LOG = LoggerFactory.getLogger(TravelTimeReducer.class);

    private boolean calculateAccessibility;

    private boolean calculateTravelTimes;

    /** Cumulative opportunities accessibility at one particular origin. null if we're only recording travel times. */
    private AccessibilityResult accessibilityResult = null;

    /** Travel time results reduced to a limited number of percentiles. null if we're only recording accessibility. */
    private TravelTimeResult travelTimeResult = null;

    private PathResult pathResult = null;

    /** If we are calculating accessibility, the PointSets containing opportunities. */
    private PointSet[] destinationPointSets;

    /** The array indexes at which we'll find each percentile in a sorted list of length timesPerDestination. */
    private final int[] percentileIndexes;

    /** The number of different percentiles that were requested. */
    private final int nPercentiles;

    /** The travel time cutoffs supplied in the request, validated and converted to seconds. */
    private int[] cutoffsSeconds;

    /** The length of the cutoffs array, just for convenience and code clarity. */
    private int nCutoffs;

    /**
     * For each cutoff, the travel time in seconds at and above which the decay function will return zero weight.
     * These are pre-calculated and retained because the default method for finding the zero point is by bisection,
     * which should not be repeated in a tight loop.
     */
    private int[] zeroPointsForCutoffs;

    /**
     * The number of travel times we will record at each destination.
     * This is affected by the number of Monte Carlo draws requested and the departure time window.
     */
    private final int timesPerDestination;

    /** Provides a weighting factor for opportunities at a given travel time. */
    private final DecayFunction decayFunction;

    /**
     * Reduce travel time values to requested summary outputs for each origin. The type of output (a single
     * cumulative opportunity accessibility value per origin, or selected percentiles of travel times to all
     * destinations) is determined based on the provided task.
     *
     * If the task is a RegionalTask and does not include an originPointSetKey or a value of true for the
     * makeTauiSite flag, travel times will be reduced to an accessibility value per origin. If a RegionalTask
     * includes an originPointSetKey, travel times from the origins to the destinations of the destinationPointSetKey
     * will be retained. Accessibility values for freeform origin pointsets are not yet saved; this is marked as a
     * to-do below.
     *
     * The task is also used to determine the number of timesPerDestination, which depends on whether the  task
     * specifies an inRoutingFareCalculator. A non-null inRoutingFareCalculator is used as a flag for the
     * multi-criteria McRaptor router, which is relatively slow, so it relies on sampling (using a number of
     * departure times specified by task.monteCarloDraws). FastRaptorworker is fast enough to run Monte Carlo draws
     * within departure minutes, so it uses the monteCarloDraws parameter in a way that's consistent with its name.
     *
     * @param task task to be performed.
     */
    public TravelTimeReducer (AnalysisWorkerTask task) {

        // Set timesPerDestination depending on how waiting time/travel time variability will be sampled
        if (task.inRoutingFareCalculator != null) {
            // Calculating fares within routing (using the McRaptor router) is slow, so sample at different
            // departure times (rather than sampling multiple draws at every minute in the departure time window).
            this.timesPerDestination = task.monteCarloDraws;
        } else {
            if (task.monteCarloDraws == 0) {
                // HALF_HEADWAY boarding, returning a single travel time per departure minute per destination.
                this.timesPerDestination = task.getTimeWindowLengthMinutes();
            } else {
                // MONTE_CARLO boarding, using several different randomized schedules at each departure time.
                this.timesPerDestination = task.getTimeWindowLengthMinutes() * task.getMonteCarloDrawsPerMinute();
            }
        }

        // Validate and process the travel time percentiles.
        // We pre-compute the indexes at which we'll find each percentile in a sorted list of the given length.
        task.validatePercentiles();
        this.nPercentiles = task.percentiles.length;
        this.percentileIndexes = new int[nPercentiles];
        for (int p = 0; p < nPercentiles; p++) {
            percentileIndexes[p] = findPercentileIndex(timesPerDestination, task.percentiles[p]);
        }

        // Decide whether we want to retain travel times to all destinations for this origin.
        // This is currently only used with regional tasks when origins are freeform pointsets.
        // This base TravelTimeResult class (as opposed to its subclass TimeGrid) does not have grid writing
        // capabilities, which are not needed or relevant in non-Taui regional analyses as they report directly
        // back to the broker in JSON.

        // Decide which elements we'll be calculating, retaining, and returning.
        // Always copy this field, the array in the task may be null or empty but we detect that case.
        this.destinationPointSets = task.destinationPointSets;
        if (task instanceof TravelTimeSurfaceTask) {
            calculateTravelTimes = true;
            calculateAccessibility = notNullOrEmpty(task.destinationPointSets);
        } else {
            // Maybe we should define recordAccessibility and recordTimes on the common superclass AnalysisWorkerTask.
            RegionalTask regionalTask = (RegionalTask) task;
            calculateAccessibility = regionalTask.recordAccessibility;
            calculateTravelTimes = regionalTask.recordTimes || regionalTask.makeTauiSite;
        }

        // Instantiate and initialize objects to accumulate the kinds of results we expect to produce.
        // These are conditionally instantiated because they can consume a lot of memory.
        if (calculateAccessibility) {
            accessibilityResult = new AccessibilityResult(task);
        }
        if (calculateTravelTimes) {
            travelTimeResult = new TravelTimeResult(task);
            pathResult = new PathResult(task);
        }

        // Validate and copy the travel time cutoffs, converting them to seconds to avoid repeated multiplication
        // in tight loops. Also find the points where the decay function reaches zero for these cutoffs.
        // This is only relevant when calculating accessibility.
        this.decayFunction = task.decayFunction;
        if (calculateAccessibility) {
            task.validateCutoffsMinutes();
            this.nCutoffs = task.cutoffsMinutes.length;
            this.cutoffsSeconds = new int[nCutoffs];
            this.zeroPointsForCutoffs = new int[nCutoffs];
            for (int c = 0; c < nCutoffs; c++) {
                final int cutoffSeconds = task.cutoffsMinutes[c] * 60;
                this.cutoffsSeconds[c] = cutoffSeconds;
                this.zeroPointsForCutoffs[c] = decayFunction.reachesZeroAt(cutoffSeconds);
            }
        }

    }


    /**
     * Compute the index into a sorted list of N elements at which a particular percentile will be found. Our
     * method does not interpolate, it always reports a value actually appearing in the list of elements. That is
     * to say, the percentile will be found at an integer-valued index into the sorted array of elements. The
     * definition of a non-interpolated percentile is as follows: the smallest value in the list such that no more
     * than P percent of the data is strictly less than the value and at least P percent of the data is less than
     * or equal to that value. By this definition, the 100th percentile is the largest value in the list. See
     * https://en.wikipedia.org/wiki/Percentile#Definitions
     * <p>
     * The formula given on Wikipedia next to definition cited above uses ceiling for one-based indexes. It is
     * tempting to just truncate to ints instead of ceiling but this gives different results on integer
     * boundaries.
     */
    private static int findPercentileIndex(int nElements, double percentile) {
        return (int)(Math.ceil(percentile / 100 * nElements) - 1);
    }

    /**
     * Given a single unvarying travel time to a destination, replicate it to match the expected number of
     * percentiles, then record those n identical percentiles at the target.
     *
     * @param timeSeconds a single travel time for results with no variation, e.g. from walking, biking, or driving.
     */
    public void recordUnvaryingTravelTimeAtTarget (int target, int timeSeconds){
        int[] travelTimePercentilesSeconds = new int[nPercentiles];
        Arrays.fill(travelTimePercentilesSeconds, timeSeconds);
        recordTravelTimePercentilesForTarget(target, travelTimePercentilesSeconds);
    }

    /**
     * Given a list of travel times of the expected length, extract the requested percentiles, then record those values
     * at the specified target. WARNING: this method destructively sorts the supplied times travel in place.
     * Their positions in the array will no longer correspond to the raptor iterations that produced them.
     *
     * @param timesSeconds which will be destructively sorted in place to extract percentiles.
     */
    public void extractTravelTimePercentilesAndRecord (int target, int[] timesSeconds) {
        checkArgument(timesSeconds.length == timesPerDestination,
            "Number of times supplied must match the number of iterations in this search.");
        for (int i : timesSeconds) {
            checkArgument(i >= 0, "Travel times must be positive.");
        }

        // Sort the travel times to this target and extract percentiles at the pre-calculated percentile indexes.
        // We used to convert these to minutes before sorting, which may allow the sort to be more efficient.
        // We even had a prototype counting sort that would take advantage of this detail. However, applying distance
        // decay functions with one-second resolution decreases sensitivity to randomization error in travel times.
        Arrays.sort(timesSeconds);
        int[] percentileTravelTimesSeconds = new int[nPercentiles];
        for (int p = 0; p < nPercentiles; p++) {
            percentileTravelTimesSeconds[p] = timesSeconds[percentileIndexes[p]];
        }
        recordTravelTimePercentilesForTarget(target, percentileTravelTimesSeconds);
    }

    /**
     * Given a list of travel times in seconds, one for each percentile, store these percentiles of travel time
     * at a particular target location and/or store the derived accessibility values at the origin location. Note that
     * when handling a single point analysis we will receive 5 percentiles here since the UI requests all 5 at once.
     */
    private void recordTravelTimePercentilesForTarget (int target, int[] travelTimePercentilesSeconds) {
        checkArgument(travelTimePercentilesSeconds.length == nPercentiles,
                "Must supply exactly as many travel times as there are percentiles.");
        for (int i : travelTimePercentilesSeconds) {
            checkArgument(i >= 0, "Travel times must be positive.");
        }
        if (calculateTravelTimes) {
            int[] percentileTravelTimesMinutes = new int[nPercentiles];
            for (int p = 0; p < nPercentiles; p++) {
                percentileTravelTimesMinutes[p] = convertToMinutes(travelTimePercentilesSeconds[p]);
            }
            travelTimeResult.setTarget(target, percentileTravelTimesMinutes);
        }
        if (calculateAccessibility) {
            // This can handle multiple opportunity grids as long as they have exactly the same extents.
            // Grids of different extents are handled by using GridTransformWrapper to give them all the same extents.
            for (int d = 0; d < destinationPointSets.length; d++) {
                final double opportunityCountAtTarget = destinationPointSets[d].getOpportunityCount(target);
                if (!(opportunityCountAtTarget > 0)) {
                    continue;
                }
                for (int p = 0; p < nPercentiles; p++) {
                    final int travelTimeSeconds = travelTimePercentilesSeconds[p];
                    if (travelTimeSeconds == FastRaptorWorker.UNREACHED) {
                        // Percentiles should be sorted. If one is UNREACHED or above a cutoff, the rest will also be.
                        // This check is somewhat redundant since by virtue of being MAX_INT, UNREACHED is necessarily
                        // greater than or equal to the decay function's zero point at the highest cutoff.
                        break;
                    }
                    // Iterate backward through sorted cutoffs, to allow early bail-out when travel time exceeds the
                    // point where the decay function reaches zero weight.
                    for (int c = nCutoffs - 1; c >= 0; c--) {
                        final int cutoffSeconds = cutoffsSeconds[c];
                        if (travelTimeSeconds >= zeroPointsForCutoffs[c]) {
                            break;
                        }
                        // Precomputing travel weight factors does not seem practical, as it would involve a
                        // 7200x7200 matrix containing about 415MB of coefficients. Reading through that much memory
                        // may well be slower than computing the coefficient each time it's needed.
                        double weightFactor = decayFunction.computeWeight(cutoffSeconds, travelTimeSeconds);
                        if (weightFactor > 0) {
                            double weightedOpportunityCount = opportunityCountAtTarget * weightFactor;
                            accessibilityResult.incrementAccessibility(d, p, c, weightedOpportunityCount);
                        }
                    }
                }
            }
        }
    }

    public void recordPathsForTarget (int target, Path[] perIterationPaths) {
        Multimap<Path, Integer> iterationNumbersForPath = HashMultimap.create();
        for (int i = 0; i < perIterationPaths.length; i++) {
            iterationNumbersForPath.put(perIterationPaths[i], i);
        }
        pathResult.setTarget(target, iterationNumbersForPath);
    }

    /**
     * Convert the given timeSeconds to minutes, being careful to preserve UNREACHED values.
     * The seconds to minutes conversion uses integer division, which truncates toward zero. This approach is correct
     * for use in accessibility analysis, where we are always testing whether a travel time is less than a certain
     * threshold value. For example, all travel times between 59 and 60 minutes will truncate to 59, and will
     * correctly return true for the expression (t < 60 minutes). We are converting seconds to minutes before we
     * export a binary format mainly to narrow the times so they fit into single bytes (though this also reduces
     * entropy and makes compression more effective). Arguably this couplings the backend too closely to the frontend
     * (which makes use of UInt8 typed arrays); the frontend could in principle receive a more general purpose format
     * using wider or variable width integers.
     * TODO revise Javadoc - these values don't seem to ever be used in accessibility or reported to the UI.
     */
    private int convertToMinutes (int timeSeconds) {
        // This check is a bit redundant, UNREACHED is always >= any integer pruning threshold.
        if (timeSeconds == UNREACHED) {
            return UNREACHED;
        } else {
            int timeMinutes = timeSeconds / FastRaptorWorker.SECONDS_PER_MINUTE;
            return timeMinutes;
        }
    }

    /**
     * This is the primary way to create a OneOriginResult and end the processing.
     * Some alternate code paths exist for TAUI site generation and testing, but this handles all other cases.
     * For example, if no travel times to destinations have been streamed in by calling recordTravelTimesForTarget, the
     * TimeGrid will have a buffer full of UNREACHED. This allows shortcutting around routing and propagation when the
     * origin point is not connected to the street network.
     */
    public OneOriginResult finish () {
        return new OneOriginResult(travelTimeResult, accessibilityResult, pathResult);
    }

    /**
     * Sanity check: all opportunity data sets should have the same size and location as the points to which we'll
     * calculate travel times. They will only be used if we're calculating accessibility.
     */
    public void checkOpportunityExtents (PointSet travelTimePointSet) {
        if (calculateAccessibility) {
            for (PointSet opportunityPointSet : destinationPointSets) {
                checkState(opportunityPointSet.getWebMercatorExtents().equals(travelTimePointSet.getWebMercatorExtents()),
                        "Travel time would be calculated to a PointSet that does not match the opportunity PointSet.");
            }
        }
    }
}
