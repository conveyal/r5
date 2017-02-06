package com.conveyal.r5.analyst;

import com.amazonaws.services.sqs.AmazonSQS;
import com.amazonaws.services.sqs.AmazonSQSClient;
import com.amazonaws.services.sqs.model.MessageAttributeValue;
import com.amazonaws.services.sqs.model.SendMessageRequest;
import com.conveyal.r5.analyst.cluster.GridRequest;
import com.conveyal.r5.analyst.cluster.Origin;
import com.conveyal.r5.analyst.cluster.TaskStatistics;
import com.conveyal.r5.api.util.LegMode;
import com.conveyal.r5.profile.RaptorWorker;
import com.conveyal.r5.profile.StreetMode;
import com.conveyal.r5.streets.LinkedPointSet;
import com.conveyal.r5.streets.StreetRouter;
import com.conveyal.r5.transit.TransportNetwork;
import com.google.common.io.LittleEndianDataOutputStream;
import gnu.trove.iterator.TIntIntIterator;
import gnu.trove.map.TIntIntMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
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

        RaptorWorker router = new RaptorWorker(network.transitLayer, linkedTargets, request.request);

        TIntIntMap reachedStops = sr.getReachedStops();

        // convert millimeters to seconds
        int millimetersPerSecond = (int) (request.request.walkSpeed * 1000);
        for (TIntIntIterator it = reachedStops.iterator(); it.hasNext();) {
            it.advance();
            it.setValue(it.value() / millimetersPerSecond);
        }

        // Run the raptor algorithm
        router.runRaptor(reachedStops, linkedTargets.eval(sr::getTravelTimeToVertex), new TaskStatistics());

        // save the instantaneous accessibility at each minute/iteration, later we will use this to compute probabilities
        // of improvement.
        // This means we have the fungibility issue described in AndrewOwenMeanGridStatisticComputer.
        int[] accessibilityPerIteration = new int[router.includeInAverages.cardinality()];

        // skip the upper and lower bounds, as they should definitely not be used in probabilistic comparison,
        // that would definitely constitute Dilbert statistics.
        for (int i = router.includeInAverages.nextSetBit(0), out = 0; i != -1; i = router.includeInAverages.nextSetBit(i + 1)) {
            int[] times = router.timesAtTargetsEachIteration[i];
            double access = 0;

            // times in row-major order, convert to grid coordinates
            // TODO use consistent grids for all data in a project
            for (int gridy = 0; gridy < grid.height; gridy++) {
                int reqy = gridy + grid.north - request.north;
                if (reqy < 0 || reqy >= request.height) continue; // outside of project bounds

                for (int gridx = 0; gridx < grid.width; gridx++) {
                    int reqx = gridx + grid.west - request.west;
                    if (reqx < 0 || reqx >= request.width) continue; // outside of project bounds

                    int index = reqy * request.width + reqx;
                    if (times[index] < request.cutoffMinutes * 60) {
                        access += grid.grid[gridx][gridy];
                    }
                }
            }

            accessibilityPerIteration[out++] = (int) Math.round(access);
        }

        // now construct the output
        // these things are tiny, no problem storing in memory
        ByteArrayOutputStream baos = new ByteArrayOutputStream();

        new Origin(request, accessibilityPerIteration).write(baos);

        // send this origin to an SQS queue as a binary payload; it will be consumed by GridResultConsumer
        // and GridResultAssembler
        SendMessageRequest smr = new SendMessageRequest(request.outputQueue, base64.encodeToString(baos.toByteArray()));
        smr = smr.addMessageAttributesEntry("jobId", new MessageAttributeValue().withDataType("String").withStringValue(request.jobId));
        sqs.sendMessage(smr);
    }
}
