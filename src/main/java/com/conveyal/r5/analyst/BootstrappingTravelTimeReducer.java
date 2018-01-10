package com.conveyal.r5.analyst;

import com.amazonaws.services.sqs.AmazonSQS;
import com.amazonaws.services.sqs.AmazonSQSClient;
import com.amazonaws.services.sqs.model.MessageAttributeValue;
import com.amazonaws.services.sqs.model.SendMessageRequest;
import com.conveyal.r5.analyst.cluster.Origin;
import com.conveyal.r5.analyst.cluster.RegionalTask;
import com.conveyal.r5.profile.PerTargetPropagater;
import gnu.trove.list.TIntList;
import gnu.trove.list.array.TIntArrayList;
import org.apache.commons.math3.random.MersenneTwister;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Base64;
import java.util.stream.DoubleStream;

/**
 * Computes an accessibility indicator at a single origin cell in a Web Mercator grid, using destination densities from
 * a Web Mercator density grid. Both grids must be at the same zoom level. This class computes accessibility given a
 * certain percentile of the travel times to each destination (described below and in Conway, Byrd and van Eggermond 2017).
 *
 * NOTE THIS NO LONGER DOES ANY BOOTSTRAPPING, the name is a remnant of additional computations it used to do.
 * TODO This is in fact just a point estimate, and you will get different results when re-running the MC draws.
 * We have a bootstrapping method for estimating the error, but we've decided to hide that from the user, using it as
 * a calibration technique for setting a sufficient number of MC draws.
 *
 * The accessibility is calculated using a percentile of travel time to all destinations (there are plans to change
 * this to allow use of multiple percentiles).
 */
public class BootstrappingTravelTimeReducer implements PerTargetPropagater.TravelTimeReducer {

    /** SQS client and Base64 encoding: to be removed with new broker. */
    private static final AmazonSQS sqs = new AmazonSQSClient();
    private static final Base64.Encoder base64 = Base64.getEncoder();

    private final RegionalTask task;

    /** Accessibility result for this origin. */
    private double accessibility = 0;

    /** Destination opportunity density grid */
    public final Grid grid;

    /** Minimum number of times a destination must be reached to be considered reachable */
    private final int minCount;

    /**
     * As an optimization, since we only care whether a cell was reached and not exactly how long it took to reach,
     * we don't need to sort the travel times to a destination and read off specific percentiles of travel time.
     *
     * Sorting is O(n.log(n)) and this function is called up to millions of times in a tight loop.
     * We can decide whether a destination is reachable in a single sub-O(n) pass if we don't need to keep the times.
     * We pre-calculate how many of the travel times would need to fall under the threshold to attain a certain
     * percentile. We then count how many of the reported travel times fall under the threshold travel time and bail
     * out early as soon as that count is reached.
     *
     * A destination count is added into the accessibility result if the p-th percentile of travel time falls under
     * the threshold. If the 50th percentile travel time is below the threshold, that means that at least 50% of the
     * observations are below the threshold. If the 90th percentile travel time is below the threshold, that means that
     * at least 90% of the observations are below the threshold.
     *
     * TODO verify that this optimization actually affects runtime in the non-bootstrapping case.
     */
    public BootstrappingTravelTimeReducer (RegionalTask request, Grid grid) {
        this.task = request;
        this.grid = grid;
        int nMinutes = request.getTimeWindowLengthMinutes();
        int monteCarloDrawsPerMinute = request.getMonteCarloDrawsPerMinute();
        // TODO handle multiple percentiles, request already has an array to hold more than one of them
        if (request.percentiles.length != 1) {
            throw new IllegalArgumentException("Bootstrapped travel times only support a single percentile of travel time!");
        }
        int nIterations = nMinutes * monteCarloDrawsPerMinute;
        // The disatance between the first value (0th percentile) and last value (100th percentile) is nIterations-1.
        // minCount should range from 1 (for 0th percentile / minimum) to N (for 100th percentile / maximum)
        minCount = (int) ((nIterations - 1) * (request.percentiles[0] / 100d)) + 1;
    }

    // TODO rename, this does not "record" the travel times, it consumes them or processes them
    @Override
    public void recordTravelTimesForTarget(int target, int[] travelTimesForTarget) {
        // We use the size of the grid to determine the number of destinations used in the linked point set in
        // TravelTimeComputer, therefore the target indices are relative to the grid, not the task.
        // TODO verify that the above is still accurate
        int gridx = target % grid.width;
        int gridy = target / grid.width;
        double opportunityCountAtTarget = grid.grid[gridx][gridy];

        // As an optimization, don't even bother to check whether cells that contain no opportunities are reachable.
        if (opportunityCountAtTarget < 1e-6) return;

        // If there is no variation in travel time to the destination, there is no need to compute percentiles.
        // This happens with non-transit modes like biking and walking.
        if (travelTimesForTarget.length == 1) {
            if (travelTimesForTarget[0] < task.maxTripDurationMinutes * 60) {
                accessibility += opportunityCountAtTarget;
            }
            return;
        }

        // See if more than the minimum number of travel times are below the threshold.
        // Bail out early once we've reached that minimum.
        int count = 0;
        for (int i = 0; i < travelTimesForTarget.length && count < minCount; i++) {
            if (travelTimesForTarget[i] < task.maxTripDurationMinutes * 60) count += 1;
        }
        // TODO Maybe sigmoidal rolloff here, to soften artifacts from large destinations that jump a few seconds in or out of the cutoff.
        if (count >= minCount) {
            accessibility += opportunityCountAtTarget;
        }
    }

    /**
     * Write the origin to SQS.
     * If no travel times to destinations have been streamed in by calling recordTravelTimesForTarget, the
     * bootstrapReplicationsOfAccessibility will all still be zero and the output will be zero, which allows
     * shortcutting around routing and propagation when the origin point is not connected to the street network.
     */
    @Override
    public void finish () {
        // now construct the output
        // these things are tiny, no problem storing in memory
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        // Only one accessibility figure
        int[] intReplications = new int[] { (int) Math.round(accessibility) };

        try {
            new Origin(task, task.percentiles[0], intReplications).write(baos);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        // send this origin to an SQS queue as a binary payload; it will be consumed by GridResultQueueConsumer
        // and GridResultAssembler
        SendMessageRequest smr = new SendMessageRequest(task.outputQueue, base64.encodeToString(baos.toByteArray()));
        smr = smr.addMessageAttributesEntry("jobId", new MessageAttributeValue().withDataType("String").withStringValue(task.jobId));
        sqs.sendMessage(smr);
    }

}
