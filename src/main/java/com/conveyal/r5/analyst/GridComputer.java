package com.conveyal.r5.analyst;

import com.amazonaws.services.sqs.AmazonSQS;
import com.amazonaws.services.sqs.AmazonSQSClient;
import com.amazonaws.services.sqs.model.MessageAttributeValue;
import com.amazonaws.services.sqs.model.SendMessageRequest;
import com.conveyal.r5.analyst.cluster.GridRequest;
import com.conveyal.r5.analyst.cluster.Origin;
import com.conveyal.r5.analyst.cluster.TaskStatistics;
import com.conveyal.r5.api.util.LegMode;
import com.conveyal.r5.profile.FastRaptorWorker;
import com.conveyal.r5.profile.PerTargetPropagater;
import com.conveyal.r5.profile.Propagater;
import com.conveyal.r5.profile.RaptorWorker;
import com.conveyal.r5.profile.StreetMode;
import com.conveyal.r5.streets.LinkedPointSet;
import com.conveyal.r5.streets.StreetRouter;
import com.conveyal.r5.transit.TransportNetwork;
import com.google.common.io.LittleEndianDataOutputStream;
import gnu.trove.iterator.TIntIntIterator;
import gnu.trove.list.TIntList;
import gnu.trove.list.array.TIntArrayList;
import gnu.trove.map.TIntIntMap;
import org.apache.commons.math3.random.MersenneTwister;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Base64;
import java.util.BitSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.DoubleStream;
import java.util.stream.Stream;

/**
 * Computes an accessibility indicator at a single cell in a Web Mercator grid, using destination densities from
 * a Web Mercator density grid. Both grids must be at the same zoom level. The accessibility is calculated using
 * median travel time (there are plans to change this to allow use of arbitrary percentiles in the future). In order
 * to facilitate probabilistic comparison of scenarios, many accessibility values are returned, representing the
 * sampling distribution of the accessibility. The first value is the value computed using all the Monte Carlo frequency
 * draws with equal weight. Subsequent values are produced by bootstrapping the Monte Carlo iterations to simulate
 * the sampling distribution. This reflects sampling from the Monte Carlo draws _with replacement_ to approximate the
 * effects of repeating the analysis. Sounds crazy but it works, see Efron, Bradley, and Robert J Tibshirani.
 * An Introduction to the Bootstrap, Boca Raton, FL: Chapman and Hall/CRC, 1993.
 *
 * The results are placed on an Amazon SQS queue for collation by a GridResultConsumer and a GridResultAssembler.
 */
public class GridComputer  {
    private static final Logger LOG = LoggerFactory.getLogger(GridComputer.class);

    /** The number of iterations used to bootstrap the sampling distribution of the percentiles */
    public static final int BOOTSTRAP_ITERATIONS = 1000;

    /** SQS client. TODO: async? */
    private static final AmazonSQS sqs = new AmazonSQSClient();

    private static final Base64.Encoder base64 = Base64.getEncoder();

    private final GridCache gridCache;

    public final GridRequest request;

    private static WebMercatorGridPointSetCache pointSetCache = new WebMercatorGridPointSetCache();

    public final TransportNetwork network;

    public GridComputer(GridRequest request, GridCache gridCache, TransportNetwork network) {
        this.request = request;
        this.gridCache = gridCache;
        this.network = network;
    }

    public void run() throws IOException {
        final Grid grid = gridCache.get(request.grid);

        // ensure they both have the same zoom level
        if (request.zoom != grid.zoom) throw new IllegalArgumentException("grid zooms do not match!");

        // use the middle of the grid cell
        request.request.fromLat = Grid.pixelToLat(request.north + request.y + 0.5, request.zoom);
        request.request.fromLon = Grid.pixelToLon(request.west + request.x + 0.5, request.zoom);

        // Run the raptor algorithm to get times at each destination for each iteration

        // first, find the access stops
        StreetMode mode;
        if (request.request.accessModes.contains(LegMode.CAR)) mode = StreetMode.CAR;
        else if (request.request.accessModes.contains(LegMode.BICYCLE)) mode = StreetMode.BICYCLE;
        else mode = StreetMode.WALK;

        LOG.info("Maximum number of rides: {}", request.request.maxRides);
        LOG.info("Maximum trip duration: {}", request.request.maxTripDurationMinutes);

        // Use the extent of the grid as the targets; this avoids having to convert between coordinate systems,
        // and avoids including thousands of extra points in the weeds where there happens to be a transit stop.
        WebMercatorGridPointSet targets = pointSetCache.get(grid);
        // TODO recast using egress mode
        final LinkedPointSet linkedTargets = targets.link(network.streetLayer, mode);

        StreetRouter sr = new StreetRouter(network.streetLayer);
        sr.distanceLimitMeters = 2000;
        sr.setOrigin(request.request.fromLat, request.request.fromLon);
        sr.dominanceVariable = StreetRouter.State.RoutingVariable.DISTANCE_MILLIMETERS;
        sr.route();

        TIntIntMap reachedStops = sr.getReachedStops();

        // convert millimeters to seconds
        int millimetersPerSecond = (int) (request.request.walkSpeed * 1000);
        for (TIntIntIterator it = reachedStops.iterator(); it.hasNext();) {
            it.advance();
            it.setValue(it.value() / millimetersPerSecond);
        }

        FastRaptorWorker router = new FastRaptorWorker(network.transitLayer, request.request, reachedStops);

        // Run the raptor algorithm
        int[][] timesAtStopsEachIteration = router.route();

        // compute bootstrap weights
        // first, some terminology: a bootstrap replication is the statistic of interest defined on one bootstrap sample,
        // which is a collection of values sampled from the original Monte Carlo draws.
        // for each of the bootstrap samples we compute, we define which Monte Carlo draws should be included. We want
        // to choose the iterations included in a particular bootstrap sample here, rather than choosing different
        // iterations at each destination. The most intuitive explanation for why this is so is that this matches the
        // way our accessibility indicator is defined: each Monte Carlo draw is used at every destination, not just some
        // of them. Sampling independently at each destination causes insufficient variance in the resulting sampling
        // distribution. Extreme values of travel time tend to be correlated across many destinations; a particular Monte
        // Carlo draw is likely to affect the travel time to all destinations reached by it simultaneously; in the most
        // extreme case, a change in the Monte Carlo draw on the line serving the origin could make the whole network
        // reachable, or not, within the cutoff.

        // This leads us to a more theoretical justification. One of the tenets of the bootstrap is that the
        // bootstrap samples be independent (Efron and Tibshirani 1993, 46). In situations where the data are not
        // independent and identically distributed, a number of techniques, e.g. the moving block bootstrap, have been developed
        // (see Lahiri, S. N. Resampling Methods for Dependent Data, New York: Springer, 2003.) Most of these methods
        // consist of changes to the bootstrap sampling technique to ensure the samples are independent, and the dependence
        // structure of the data is wrapped up within a sample. While none of the off-the-shelf approaches for dependent
        // data appear to be helpful for our use case, since we know (or can speculate) the correlation properties of our
        // data, we can come up with a sampling technique. Since there is a dependence among destinations (many or all
        // being affected by the same Monte Carlo draw), we sample the Monte Carlo draws identically across destinations.

        // There is also dependence in the departure minutes; Monte Carlo draws from the same departure minute will be
        // more similar than those from different departure minutes, and adjacent departure minutes will have correlated
        // accessibility values. There are also likely periodic effects at the transit service frequency (e.g. 15 minutes,
        // 30 minutes, etc.). In testing in Atlanta we did not find ignoring this dependence to be an issue, but it may
        // make sense to test using a scenario where the scheduled routes play more of a role (the frequency routes in
        // our test scenario provided much of the accessibility). See http://rpubs.com/mattwigway/bootstrap-dependence
        // In any case, patching this up is fairly simple. Rather than drawing n Monte Carlo draws with replacement for
        // each bootstrap sample, we draw n / m from each departure minute. This will probably reduce the variance of our
        // bootstrap samples though.

        // the Mersenne Twister is a fast, high-quality RNG well-suited to Monte Carlo situations
        MersenneTwister twister = new MersenneTwister();
        int[][] bootstrapWeights = new int[BOOTSTRAP_ITERATIONS + 1][router.nMinutes * router.monteCarloDrawsPerMinute];

        Arrays.fill(bootstrapWeights[0], 1); // equal weight to all observations for first sample

        for (int bootstrap = 1; bootstrap < bootstrapWeights.length; bootstrap++) {
            for (int draw = 0; draw < timesAtStopsEachIteration.length; draw++) {
                bootstrapWeights[bootstrap][twister.nextInt(timesAtStopsEachIteration.length)]++;
            }
        }

        // Do propagation
        int[] nonTransferTravelTimesToStops = linkedTargets.eval(sr::getTravelTimeToVertex).travelTimes;
        PerTargetPropagater propagater =
                new PerTargetPropagater(timesAtStopsEachIteration, nonTransferTravelTimesToStops, linkedTargets, request.request, request.cutoffMinutes * 60);

        // compute the percentiles
        double[] samples = new double[BOOTSTRAP_ITERATIONS + 1];

        propagater.propagate((target, reachable) -> {
            int gridx = target % grid.width;
            int gridy = target / grid.width;
            double opportunityCountAtTarget = grid.grid[gridx][gridy];

            if (opportunityCountAtTarget < 1e-6) return; // don't bother with destinations that contain no opportunities

            // index the reachable iterations, so we can skip over the non-reachable ones in bootstrap computations
            // this improves computation speed (verified)
            TIntList reachableInIterationsList = new TIntArrayList();

            for (int i = 0; i < reachable.length; i++) {
                if (reachable[i]) reachableInIterationsList.add(i);
            }

            int[] reachableInIterations = reachableInIterationsList.toArray();

            boolean foundUnreachable = reachableInIterations.length < timesAtStopsEachIteration.length;
            boolean foundReachable = reachableInIterations.length > 0;

            if (foundReachable && foundUnreachable) {
                // This origin is sometimes reachable within the time window, do bootstrapping to determine
                // the distribution of how often
                BOOTSTRAP:
                for (int bootstrap = 0; bootstrap < BOOTSTRAP_ITERATIONS + 1; bootstrap++) {
                    int count = 0;
                    for (int iteration : reachableInIterations) {
                        count += bootstrapWeights[bootstrap][iteration];
                    }

                    if (count > timesAtStopsEachIteration.length / 2) {
                        samples[bootstrap] += opportunityCountAtTarget;
                    }
                }
            } else if (foundReachable && !foundUnreachable) {
                // this destination is always reachable and will be included in all bootstrap samples, no need to do the
                // bootstrapping
                for (int i = 0; i < samples.length; i++) samples[i] += grid.grid[gridx][gridy];
            } // otherwise, this destination is never reachable, no need to do bootstrapping or increment samples
        });

        int[] intSamples = DoubleStream.of(samples).mapToInt(d -> (int) Math.round(d)).toArray();

        // now construct the output
        // these things are tiny, no problem storing in memory
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        new Origin(request, intSamples).write(baos);

        // send this origin to an SQS queue as a binary payload; it will be consumed by GridResultConsumer
        // and GridResultAssembler
        SendMessageRequest smr = new SendMessageRequest(request.outputQueue, base64.encodeToString(baos.toByteArray()));
        smr = smr.addMessageAttributesEntry("jobId", new MessageAttributeValue().withDataType("String").withStringValue(request.jobId));
        sqs.sendMessage(smr);
    }
}
