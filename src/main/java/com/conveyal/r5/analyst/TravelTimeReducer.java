package com.conveyal.r5.analyst;

import com.beust.jcommander.ParameterException;
import com.conveyal.r5.OneOriginResult;
import com.conveyal.r5.analyst.cluster.AnalysisTask;
import com.conveyal.r5.analyst.cluster.RegionalTask;
import com.conveyal.r5.analyst.cluster.TravelTimeResult;
import com.conveyal.r5.analyst.cluster.TravelTimeSurfaceTask;
import com.conveyal.r5.profile.FastRaptorWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;

import static com.conveyal.r5.profile.FastRaptorWorker.UNREACHED;

/**
 * Given a bunch of travel times from an origin to a single destination point, this collapses that long list into a
 * limited number of percentiles, then optionally accumulates that destination's opportunity count into the
 * appropriate cumulative opportunities accessibility indicators at that origin.
 */
public class TravelTimeReducer {

    private static final Logger LOG = LoggerFactory.getLogger(TravelTimeReducer.class);

    /** The largest number of cutoffs we'll accept in a task. Too many cutoffs can create very large output files. */
    public static final int MAX_CUTOFFS = 12;

    /**
     * Maximum total travel time, above which a destination should be considered unreachable. Note the logic in
     * analysis-backend AnalysisRequest, which sets this to the requested value for regional analyses, but keeps
     * it at the default value from R5 ProfileRequest for single-point requests (which allow adjusting the cutoff
     * after results have been calculated).
     *
     * CHANGING: this now just needs to be larger than the highest cutoff, or should be set to the max cutoff.
     */
    private final int maxTripDurationMinutes;

    private boolean calculateAccessibility;

    private boolean calculateTravelTimes;

    /**
     * Cumulative opportunities accessibility at this one particular origin.
     * May be null if we're only recording travel times.
     */
    private AccessibilityResult accessibilityResult = null;

    /** Reduced (e.g. at one percentile) travel time results. May be null if we're only recording accessibility. */
    private TravelTimeResult travelTimeResult = null;

    /** If we are calculating accessibility, the PointSets containing opportunities. */
    private final PointSet[] destinationPointSets = new PointSet[1];

    private final int[] percentileIndexes;

    private final int nPercentiles;

    private int[] cutoffsMinutes;

    private int nCutoffs;

    /**
     * The number of travel times we will record at each destination.
     * This is affected by the number of Monte Carlo draws requested and the departure time window.
     */
    private final int timesPerDestination;

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
    public TravelTimeReducer (AnalysisTask task) {

        // Before regional analysis tasks are handled, their max trip duration is forced to the highest cutoff.
        this.maxTripDurationMinutes = task.maxTripDurationMinutes;

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
        this.nPercentiles = task.percentiles.length;
        this.percentileIndexes = new int[nPercentiles];
        for (int p = 0; p < nPercentiles; p++) {
            percentileIndexes[p] = findPercentileIndex(timesPerDestination, task.percentiles[p]);
        }
        for (int p = 1; p < nPercentiles; p++) {
            if (task.percentiles[p] < task.percentiles[p-1]) {
                throw new IllegalArgumentException("Percentiles must be in ascending order.");
            }
        }

        // Decide whether we want to retain travel times to all destinations for this origin.
        // This is currently only used with regional tasks when origins are freeform pointsets.
        // This base TravelTimeResult class (as opposed to its subclass TimeGrid) does not have grid writing
        // capabilities, which are not needed or relevant in non-Taui regional analyses as they report directly
        // back to the broker in JSON.

        // Decide which elements we'll be calculating, retaining, and returning.
        calculateAccessibility = calculateTravelTimes = false;
        if (task instanceof TravelTimeSurfaceTask) {
            calculateTravelTimes = true;
        } else {
            RegionalTask regionalTask = (RegionalTask) task;
            if (regionalTask.recordAccessibility) {
                calculateAccessibility = true;
                this.destinationPointSets[0] = regionalTask.destinationPointSet;
            }
            if (regionalTask.recordTimes || regionalTask.makeTauiSite) {
                calculateTravelTimes = true;
            }
        }

        // Instantiate and initialize objects to accumulate the kinds of results we expect to produce.
        // These are conditionally instantiated because they can consume a lot of memory.
        if (calculateAccessibility) {
            accessibilityResult = new AccessibilityResult(task);
        }
        if (calculateTravelTimes) {
            travelTimeResult = new TravelTimeResult(task);
        }

        // Validate and copy the travel time cutoffs, which only makes sense when calculating accessibility.
        // Validation should probably happen earlier when making or handling incoming tasks.
        if (calculateAccessibility) {
            this.nCutoffs = task.cutoffsMinutes.length;
            if (nCutoffs > MAX_CUTOFFS) {
                throw new IllegalArgumentException("Maximum number of cutoffs allowed is " + MAX_CUTOFFS);
            }
            this.cutoffsMinutes = Arrays.copyOf(task.cutoffsMinutes, nCutoffs);
            Arrays.sort(this.cutoffsMinutes);
            if (! Arrays.equals(this.cutoffsMinutes, task.cutoffsMinutes)) {
                throw new IllegalArgumentException("Cutoffs must be in ascending order.");
            }
            for (int cutoffMinutes : this.cutoffsMinutes) {
                if (cutoffMinutes < 1 || cutoffMinutes > 120) {
                    throw new IllegalArgumentException("Accessibility time cutoffs must be in the range 1 to 120 minutes.");
                }
            }
            if (maxTripDurationMinutes < this.cutoffsMinutes[nCutoffs - 1]) {
                throw new IllegalArgumentException("Max trip duration must be at least as large as highest cutoff.");
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
     * @param timeSeconds Single travel time, for results with no variation, e.g. from walking, biking, or driving.
     */
    public void recordUnvaryingTravelTimeAtTarget (int target, int timeSeconds){
        int[] travelTimesMinutes = new int[nPercentiles];
        Arrays.fill(travelTimesMinutes, convertToMinutes(timeSeconds));
        recordTravelTimePercentilesForTarget(target, travelTimesMinutes);
    }

    /**
     * Given a list of travel times of the expected length, extract the requested percentiles, then record those values
     * at the specified target. WARNING: this method destructively sorts the supplied times travel in place.
     * Their positions in the array will no longer correspond to the raptor iterations that produced them.
     *
     * @param timesSeconds which will be destructively sorted in place to extract percentiles.
     */
    public void extractTravelTimePercentilesAndRecord (int target, int[] timesSeconds) {
        // Sort the times at each target and extract percentiles at the pre-calculated indexes.
        int[] travelTimePercentilesMinutes = new int[nPercentiles];
        if (timesSeconds.length == timesPerDestination) {
            // Instead of general purpose sort this could be done by performing a counting sort on the times,
            // converting them to minutes in the process and reusing the small histogram array (120 elements) which
            // should remain largely in processor cache. That's a lot of division though. Would need to be profiled.
            Arrays.sort(timesSeconds);
            for (int p = 0; p < nPercentiles; p++) {
                int timeSeconds = timesSeconds[percentileIndexes[p]];
                travelTimePercentilesMinutes[p] = convertToMinutes(timeSeconds);
            }
        } else {
            throw new ParameterException(timesSeconds.length + " iterations supplied; expected " + timesPerDestination);
        }
        recordTravelTimePercentilesForTarget(target, travelTimePercentilesMinutes);
    }

    /**
     * Given a list of travel times of the expected length, store the extracted percentiles of travel time (if a and/or
     * accessibility values.
     * NOTE we will actually receive 5 percentiles here in single point analysis since we request all 5 at once.
     */
    private void recordTravelTimePercentilesForTarget (int target, int[] travelTimePercentilesMinutes) {
        if (travelTimePercentilesMinutes.length != nPercentiles) {
            throw new IllegalArgumentException("Supplied number of travel times did not match percentile count.");
        }
        if (calculateTravelTimes) {
            travelTimeResult.setTarget(target, travelTimePercentilesMinutes);
        }
        if (calculateAccessibility) {
            // We only handle one grid at a time for now, because handling more than one grid will require transforming
            // indexes between multiple grids possibly of different sizes (a GridIndexTransform class?). If these are
            // for reads only, into a single super-grid, they will only need to add a single number to the width and y.
            for (int d = 0; d < 1; d++) {
                final double opportunityCountAtTarget = destinationPointSets[d].getOpportunityCount(target);
                for (int p = 0; p < nPercentiles; p++) {
                    final int travelTimeMinutes = travelTimePercentilesMinutes[p];
                    if (travelTimeMinutes == UNREACHED) {
                        // Percentiles should be sorted. If one is UNREACHED the rest will also be.
                        break;
                    }
                    // Iterate backward through sorted cutoffs, to allow early bail-out when travel time exceeds cutoff.
                    for (int c = nCutoffs - 1; c >= 0; c--) {
                        final int cutoffMinutes = cutoffsMinutes[c];
                        // Use of < here (as opposed to <=) matches the definition in JS front end, and works well when
                        // truncating seconds to minutes. This used to use TravelTimeReducer.maxTripDurationMinutes as
                        // the cutoff, now that's really the max travel time (which should be the max of all cutoffs).
                        // Might be more efficient to pass in all cutoffs at once to avoid repeated pointer math.
                        if (travelTimeMinutes < cutoffMinutes) {
                            accessibilityResult.incrementAccessibility(d, p, c, opportunityCountAtTarget);
                        } else {
                            break;
                        }
                    }
                }
            }
        }
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
        return new OneOriginResult(travelTimeResult, accessibilityResult);
    }

}
