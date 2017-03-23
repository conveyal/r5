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
import com.conveyal.r5.profile.Propagater;
import com.conveyal.r5.profile.RaptorWorker;
import com.conveyal.r5.profile.StreetMode;
import com.conveyal.r5.streets.LinkedPointSet;
import com.conveyal.r5.streets.StreetRouter;
import com.conveyal.r5.transit.TransportNetwork;
import com.google.common.io.LittleEndianDataOutputStream;
import gnu.trove.iterator.TIntIntIterator;
import gnu.trove.map.TIntIntMap;
import org.apache.commons.math3.random.MersenneTwister;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.DoubleStream;
import java.util.stream.Stream;

/**
 * Computes an accessibility indicator at a single cell in a Web Mercator grid, using destination densities from
 * a Web Mercator density grid. Both grids must be at the same zoom level. The accessibility indicator is calculated
 * separately for each iteration of the range RAPTOR algorithm (each departure minute and randomization of the schedules
 * for the frequency-based routes) and all of these different values of the indicator are retained to allow
 * probabilistic scenario comparison. This does freeze the travel time cutoff and destination grid in the interest of
 * keeping the results down to an acceptable size. The results are placed on an Amazon SQS queue for collation by
 * a GridResultConsumer and a GridResultAssembler.
 *
 * These requests are enqueued by the frontend one for each origin in a regional analysis.
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

        // NB this will skip all destinations that lie outside query bounds. This is intentional.
        final WebMercatorGridPointSet targets =
                pointSetCache.get(request.zoom, request.west, request.north, request.width, request.height);

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

        // Do propagation
        int[] nonTransferTravelTimesToStops = linkedTargets.eval(sr::getTravelTimeToVertex).travelTimes;
        Propagater propagater =
                new Propagater(timesAtStopsEachIteration, nonTransferTravelTimesToStops, linkedTargets, request.request);

        // We store a count of the number of iterations where each destination was reached within the specified time,
        // and use this to calculate percentiles.
        // This is equivalent to calculating a median and seeing if it is above or below the cutoff, but much faster
        // as it does not require storing and sorting all travel times.
        // We do this many times, using a different draw from the travel times each time, because we are creating a
        // bootstrapped sampling distribution of the percentile of interest.
        int[][] countsPerDestination = new int[BOOTSTRAP_ITERATIONS + 1][linkedTargets.size()];

        // This has the number of times to include each iteration in each bootstrapped median.
        // So if we are generating, say, 100 bootstraps on the median,
        int[][] iterationWeightsForBootstrap = new int[BOOTSTRAP_ITERATIONS + 1][timesAtStopsEachIteration.length];

        // Create the bootstrap iteration weights
        // the first bootstrap iteration is the sample median, weight every iteration equally
        Arrays.fill(iterationWeightsForBootstrap[0], 1);

        // the Mersenne Twister is a high-quality RNG well-suited to Monte Carlo situations
        MersenneTwister twister = new MersenneTwister();

        for (int i = 1; i < BOOTSTRAP_ITERATIONS + 1; i++) {
            int[] weightsForThisBootstrap = iterationWeightsForBootstrap[i];

            // Sample with replacement, the same iteration can be chosen multiple times
            for (int draw = 0; draw < weightsForThisBootstrap.length; draw++) {
                weightsForThisBootstrap[twister.nextInt(weightsForThisBootstrap.length)]++;
            }
        }

        AtomicInteger currentIteration = new AtomicInteger(0);

        propagater.propagate(times -> {
            int iteration = currentIteration.getAndIncrement();
            for (int target = 0; target < times.length; target++) {
                if (times[target] < request.cutoffMinutes * 60) {
                    for (int bootstrap = 0; bootstrap < iterationWeightsForBootstrap.length; bootstrap++) {
                        countsPerDestination[bootstrap][target] += iterationWeightsForBootstrap[bootstrap][iteration];
                    }
                }
            }

            return 0; // we're not using the per-iteration output of the propagater
        });

        // compute the percentiles
        double[] samples = new double[BOOTSTRAP_ITERATIONS + 1];

        // TODO should not be hardwired to median
        int minCount = timesAtStopsEachIteration.length / 2;

        for (int gridx = 0; gridx < grid.width; gridx++) {
            int reqx = gridx + grid.west - request.west;
            if (reqx < 0 || reqx >= request.width) continue;
            for (int gridy = 0; gridy < grid.height; gridy++) {
                int reqy = gridy + grid.north - request.north;
                if (reqy < 0 || reqy >= request.width) continue;

                double value = grid.grid[gridx][gridy];
                int targetIndex = reqy * request.width + reqx;

                for (int bootstrap = 0; bootstrap < BOOTSTRAP_ITERATIONS + 1; bootstrap++) {
                    if (countsPerDestination[bootstrap][targetIndex] > minCount) {
                        samples[bootstrap] += value;
                    }
                }
            }
        }

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
