package com.conveyal.r5.analyst;

import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.sqs.AmazonSQS;
import com.amazonaws.services.sqs.AmazonSQSAsync;
import com.amazonaws.services.sqs.AmazonSQSAsyncClient;
import com.amazonaws.services.sqs.AmazonSQSClient;
import com.amazonaws.services.sqs.model.MessageAttributeValue;
import com.amazonaws.services.sqs.model.SendMessageRequest;
import com.conveyal.r5.analyst.cluster.GridRequest;
import com.conveyal.r5.analyst.cluster.TaskStatistics;
import com.conveyal.r5.analyst.messages.OriginOuterClass;
import com.conveyal.r5.api.util.LegMode;
import com.conveyal.r5.profile.RaptorState;
import com.conveyal.r5.profile.RaptorWorker;
import com.conveyal.r5.profile.RepeatedRaptorProfileRouter;
import com.conveyal.r5.profile.StreetMode;
import com.conveyal.r5.streets.LinkedPointSet;
import com.conveyal.r5.streets.PointSetTimes;
import com.conveyal.r5.streets.StreetRouter;
import com.conveyal.r5.transit.TransportNetwork;
import com.conveyal.r5.transit.TransportNetworkCache;
import com.conveyal.r5.util.S3Util;
import com.google.common.io.LittleEndianDataOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.Base64;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;

/**
 * Computes accessibility to grids and dumps it to s3.
 */
public class GridComputer  {
    private static final Logger LOG = LoggerFactory.getLogger(GridComputer.class);

    /** SQS client. TODO: async? */
    private static final AmazonSQS sqs = new AmazonSQSClient();

    private static final Base64.Encoder base64 = Base64.getEncoder();

    private final GridCache gridCache;

    public final GridRequest request;

    public final TransportNetwork network;

    public GridComputer(GridRequest request, GridCache gridCache, TransportNetwork network) {
        this.request = request;
        this.gridCache = gridCache;
        this.network = network;
    }

    public void run() throws IOException {
        final Grid grid = gridCache.get(request.grid);

        // ensure they all have the same zoom level
        if (request.zoom != grid.zoom) throw new IllegalArgumentException("grid zooms do not match!");

        // TODO cache these so they are not relinked? or is that fast enough it doesn't matter?
        // NB this will skip all destinations that lie outside query bounds. This is intentional.
        final WebMercatorGridPointSet targets =
                new WebMercatorGridPointSet(request.zoom, request.west, request.north, request.width, request.height);

        // use the middle of the grid cell
        request.request.fromLat = Grid.pixelToLat(request.north + request.y + 0.5, request.zoom);
        request.request.fromLon = Grid.pixelToLon(request.west + request.x + 0.5, request.zoom);

        // Run the raptor algorithm to get times at each destination for each iteration
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

        router.runRaptor(sr.getReachedStops(), linkedTargets.eval(sr::getTravelTimeToVertex), new TaskStatistics());

        int[] accessibilityPerIteration = Stream.of(router.timesAtTargetsEachIteration)
                .mapToInt(times -> {
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

                    return (int) Math.round(access);
                })
                .toArray();

        // now construct the output
        // these things are tiny, no problem storing in memory
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        LittleEndianDataOutputStream data = new LittleEndianDataOutputStream(baos);

        // Write the header
        for (char c : "ORIGIN".toCharArray()) {
            data.writeByte((byte) c);
        }

        // version
        data.writeInt(0);

        data.writeInt(request.x);
        data.writeInt(request.y);

        // write the number of iterations
        data.writeInt(accessibilityPerIteration.length);

        for (int i : accessibilityPerIteration) {
            data.writeInt(i);
        }

        data.close();

        // send to SQS
        SendMessageRequest smr = new SendMessageRequest(request.outputQueue, base64.encodeToString(baos.toByteArray()));
        smr = smr.addMessageAttributesEntry("jobId", new MessageAttributeValue().withDataType("String").withStringValue(request.jobId));
        sqs.sendMessage(smr);
    }
}
