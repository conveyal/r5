package com.conveyal.r5.analyst;

import com.amazonaws.services.sqs.AmazonSQS;
import com.amazonaws.services.sqs.AmazonSQSClient;
import com.amazonaws.services.sqs.model.MessageAttributeValue;
import com.amazonaws.services.sqs.model.SendMessageRequest;
import com.conveyal.r5.analyst.cluster.GridRequest;
import com.conveyal.r5.analyst.cluster.Origin;
import com.conveyal.r5.api.util.LegMode;
import com.conveyal.r5.profile.FastRaptorWorker;
import com.conveyal.r5.profile.PerTargetPropagater;
import com.conveyal.r5.profile.StreetMode;
import com.conveyal.r5.streets.LinkedPointSet;
import com.conveyal.r5.streets.StreetRouter;
import com.conveyal.r5.transit.TransportNetwork;
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
import java.util.stream.DoubleStream;

/**
 * Computes an accessibility indicator at a single cell in a Web Mercator grid, using destination densities from
 * a Web Mercator density grid. Both grids must be at the same zoom level. This class computes accessibility given median
 * travel time (described below and in Conway, Byrd and van Eggermond 2017).
 *
 * The accessibility is calculated using
 * median travel time to each destination (there are plans to change this to allow use of arbitrary percentiles in the future). In order
 * to facilitate probabilistic comparison of scenarios, many accessibility values are returned, representing the
 * sampling distribution of the accessibility. The first value is the value computed using all the Monte Carlo frequency
 * draws with equal weight (i.e. it is our best point estimate).
 *
 * Subsequent values are produced by bootstrapping the iterations (combinations of a departure minute and a Monte Carlo draw) to simulate
 * the sampling distribution. For each value returned, we select
 * _with replacement_ a different set of Monte Carlo draws to use to approximate the effects of repeating the analysis
 * with completely new Monte Carlo draws. This yields the same number of Monte Carlo draws, but some of the draws from
 * the original analysis are not present, and some are duplicated. Sounds crazy but it works.
 *
 * First, some terminology: a bootstrap replication is the statistic of interest defined on one bootstrap sample,
 * which is a collection of values sampled with replacement from the original Monte Carlo draws.
 * For each of the bootstrap samples we compute, we define which Monte Carlo draws should be included. We want
 * to choose the Monte Carlo draws included in a particular bootstrap sample here, rather than choosing different
 * Monte Carlo draws at each destination. The most intuitive explanation for this is that this matches the
 * way our accessibility indicator is defined: each Monte Carlo draw is used at every destination, rather than
 * computing separate Monte Carlo draws for each destination. Sampling Monte Carlo draws independently at
 * each destination causes insufficient variance in the resulting sampling
 * distribution. Extreme values of travel time tend to be correlated across many destinations; a particular Monte
 * Carlo draw is likely to affect the travel time to all destinations reached by it simultaneously; in the most
 * extreme case, a change in the Monte Carlo draw on the line serving the origin could make the whole network
 * reachable, or not, within the travel time threshold.
 *
 * This leads us to a more theoretical justification. One of the tenets of the bootstrap is that the
 * bootstrap samples be independent (Efron and Tibshirani 1993, 46). In situations where the data are not
 * independent and identically distributed, a number of techniques, e.g. the moving block bootstrap, have been developed
 * (see Lahiri 2003) Most of these methods
 * consist of changes to the bootstrap sampling technique to ensure the samples are independent, and the dependence
 * structure of the data is wrapped up within a sample. While none of the off-the-shelf approaches for dependent
 * data appear to be helpful for our use case, since we know (or can speculate) the correlation properties of our
 * data, we can come up with a sampling technique. Since there is a dependence among destinations (many or all
 * being affected by the same Monte Carlo draw), we use the same bootstrap samples (consisting of Monte Carlo draws)
 * to compute the travel times to each destination.
 *
 * There is also dependence in the departure minutes; Monte Carlo draws from the same departure minute will have
 * more similar travel times and therefore accessibility values than those from different departure minutes due to the deterministic and constant effect of the
 * scheduled network. Adjacent departure minutes will additionally have correlated travel times and accessibility values
 * because transit service is similar (you might have to wait one more minute to catch the same bus).
 * There are also likely periodic effects at the transit service frequency (e.g. 15 minutes,
 * 30 minutes, etc.). In testing in Atlanta we did not find ignoring this dependence to be an issue, Patching this up is
 * fairly simple. Rather than drawing n Monte Carlo draws with replacement for
 * each bootstrap sample, we draw n / m draws for each departure minute, where n is the number of Monte Carlo draws done in
 * the original analysis, and m is the number of departure minute. That is, we ensure that the number of Monte Carlo draws
 * using a particular departure minute is identical in each bootstrap sample and the original, non-bootstrapped point
 * estimate. This also means that all the variation in the sampling distributions is due to the effect of frequency-based
 * lines, rather than due to variation due to departure time (which is held constant).
 *
 * We initially did not think that the change to sample from within the departure minutes to be that significant (see
 * http://rpubs.com/mattwigway/bootstrap-dependence). However, it in fact is. It produces narrower sampling distributions
 * that result entirely from the Monte Carlo simulation of frequency lines, without mixing in systematic variation that
 * comes from variation due to different departure times. This also should yield p-values that are more correct; because
 * there is no systematic influence from scheduled lines, 5% of the values should in fact be found statistically significant
 * when testing the same scenario (which suggests we should probably be using a higher significance threshold, since we are
 * doing thousands of computations; see Wasserstein and Lazar 2016, "The ASA's statement on p-values,"
 * http://dx.doi.org/10.1080/00031305.2016.1154108, and Ioanndis 2005, "Why Most Published Research Findings are False",
 * http://dx.doi.org/10.1371/journal.pmed.0020124.
 *
 * For more information on the bootstrap and its application to the computation of accessibility given median accessibility,
 * see
 *
 * Efron, Bradley, and Robert J Tibshirani. An Introduction to the Bootstrap, Boca Raton, FL: Chapman and Hall/CRC, 1993.
 * Lahiri, S. N. Resampling Methods for Dependent Data, New York: Springer, 2003.
 * Conway, M. W., Byrd, A. and van Eggermond, M. "A Statistical Approach to Comparing Accessibility Results: Including
 *   Uncertainty in Public Transport Sketch Planning," paper presented at the 2017 World Symposium of Transport and Land
 *   Use Research, Brisbane, Queensland, Australia, Jul 3-6.
 *
 * The results are placed on an Amazon SQS queue for collation by a GridResultConsumer and a GridResultAssembler.
 */
public class GridComputer  {
    private static final Logger LOG = LoggerFactory.getLogger(GridComputer.class);

    /** The number of bootstrap replications used to bootstrap the sampling distribution of the percentiles */
    public static final int N_BOOTSTRAP_REPLICATIONS = 1000;

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
        request.request.fromLat = Grid.pixelToCenterLat(request.north + request.y, request.zoom);
        request.request.fromLon = Grid.pixelToCenterLon(request.west + request.x, request.zoom);

        // Run the raptor algorithm to get times at each destination for each iteration

        // first, find the access stops
        StreetMode mode;
        if (request.request.accessModes.contains(LegMode.CAR)) mode = StreetMode.CAR;
        else if (request.request.accessModes.contains(LegMode.BICYCLE)) mode = StreetMode.BICYCLE;
        else mode = StreetMode.WALK;

        LOG.info("Maximum number of rides: {}", request.request.maxRides);
        LOG.info("Maximum trip duration: {}", request.request.maxTripDurationMinutes);

        // Use the extent of the opportunity density grid as the destinations; this avoids having to convert between
        // coordinate systems, and avoids including thousands of extra points in the weeds where there happens to be a
        // transit stop. It also means that all data will be included in the analysis even if the user is only analyzing
        // a small part of the city.
        WebMercatorGridPointSet destinations = pointSetCache.get(grid);
        // TODO recast using egress mode
        final LinkedPointSet linkedDestinations = destinations.link(network.streetLayer, mode);

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

        // compute bootstrap weights, see comments in Javadoc detailing how we compute the weights we're using
        // the Mersenne Twister is a fast, high-quality RNG well-suited to Monte Carlo situations
        MersenneTwister twister = new MersenneTwister();

        // This stores the number of times each Monte Carlo draw is included in each bootstrap sample, which could be
        // 0, 1 or more. We store the weights on each iteration rather than a list of iterations because it allows
        // us to easily construct the weights s.t. they sum to the original number of MC draws.
        int[][] bootstrapWeights = new int[N_BOOTSTRAP_REPLICATIONS + 1][router.nMinutes * router.monteCarloDrawsPerMinute];

        Arrays.fill(bootstrapWeights[0], 1); // equal weight to all observations for first sample

        for (int bootstrap = 1; bootstrap < bootstrapWeights.length; bootstrap++) {
            for (int minute = 0; minute < router.nMinutes; minute++) {
                for (int draw = 0; draw < router.monteCarloDrawsPerMinute; draw++) {
                    int iteration = minute * router.monteCarloDrawsPerMinute + twister.nextInt(router.monteCarloDrawsPerMinute);
                    bootstrapWeights[bootstrap][iteration]++;
                }
            }
        }

        // the minimum number of times a destination must be reachable in a single bootstrap sample to be considered
        // reachable.
        int minCount = (int) (router.nMinutes * router.monteCarloDrawsPerMinute * (request.travelTimePercentile / 100d));

        // Do propagation of travel times from transit stops to the destinations
        int[] nonTransferTravelTimesToStops = linkedDestinations.eval(sr::getTravelTimeToVertex).travelTimes;
        PerTargetPropagater propagater =
                new PerTargetPropagater(timesAtStopsEachIteration, nonTransferTravelTimesToStops, linkedDestinations, request.request, request.cutoffMinutes * 60);

        // store the accessibility results for each bootstrap replication
        double[] bootstrapReplications = new double[N_BOOTSTRAP_REPLICATIONS + 1];

        // The lambda will be called with a boolean array of whether the target is reachable within the cutoff at each
        // Monte Carlo draw. These are then bootstrapped to create the sampling distribution.
        propagater.propagate((target, reachable) -> {
            int gridx = target % grid.width;
            int gridy = target / grid.width;
            double opportunityCountAtTarget = grid.grid[gridx][gridy];

            // as an optimization, don't even bother to compute the sampling distribution at cells that contain no
            // opportunities.
            if (opportunityCountAtTarget < 1e-6) return;

            // index the Monte Carlo iterations in which the destination was reached within the travel time cutoff,
            // so we can skip over the non-reachable ones in bootstrap computations.
            // this improves computation speed (verified)
            TIntList reachableInIterationsList = new TIntArrayList();

            for (int i = 0; i < reachable.length; i++) {
                if (reachable[i]) reachableInIterationsList.add(i);
            }

            int[] reachableInIterations = reachableInIterationsList.toArray();

            boolean isAlwaysReachableWithinTravelTimeCutoff =
                    reachableInIterations.length == timesAtStopsEachIteration.length;
            boolean isNeverReachableWithinTravelTimeCutoff = reachableInIterations.length == 0;

            // Optimization: only bootstrap if some of the travel times are above the cutoff and some below.
            // If a destination is always reachable, it will perforce be reachable always in every bootstrap
            // sample, so there is no need to compute the bootstraps, and similarly if it is never reachable.
            if (isAlwaysReachableWithinTravelTimeCutoff) {
                // this destination is always reachable and will be included in all bootstrap samples, no need to do the
                // bootstrapping
                // possible optimization: have a variable that persists between calls to this lambda and increment
                // that single value, then add that value to each replication at the end; reduces the number of additions.
                for (int i = 0; i < bootstrapReplications.length; i++) bootstrapReplications[i] += grid.grid[gridx][gridy];
            } else if (isNeverReachableWithinTravelTimeCutoff) {
                // do nothing, never reachable, does not impact accessibility
            } else {
                // This origin is sometimes reachable within the time window, do bootstrapping to determine
                // the distribution of how often
                for (int bootstrap = 0; bootstrap < N_BOOTSTRAP_REPLICATIONS + 1; bootstrap++) {
                    int count = 0;
                    for (int iteration : reachableInIterations) {
                        count += bootstrapWeights[bootstrap][iteration];
                    }

                    // TODO sigmoidal rolloff here, to avoid artifacts from large destinations that jump a few seconds
                    // in or out of the cutoff.
                    if (count > minCount) {
                        bootstrapReplications[bootstrap] += opportunityCountAtTarget;
                    }
                }
            }
        });

        // round (not cast/floor) these all to ints.
        int[] intReplications = DoubleStream.of(bootstrapReplications).mapToInt(d -> (int) Math.round(d)).toArray();

        // now construct the output
        // these things are tiny, no problem storing in memory
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        new Origin(request, intReplications).write(baos);

        // send this origin to an SQS queue as a binary payload; it will be consumed by GridResultConsumer
        // and GridResultAssembler
        SendMessageRequest smr = new SendMessageRequest(request.outputQueue, base64.encodeToString(baos.toByteArray()));
        smr = smr.addMessageAttributesEntry("jobId", new MessageAttributeValue().withDataType("String").withStringValue(request.jobId));
        sqs.sendMessage(smr);
    }
}
