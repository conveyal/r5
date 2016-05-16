package com.conveyal.r5.analyst.cluster.lambda;

import com.amazonaws.services.kms.model.UnsupportedOperationException;
import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.lambda.runtime.RequestStreamHandler;
import com.conveyal.r5.analyst.WebMercatorGridPointSet;
import com.conveyal.r5.analyst.cluster.ResultEnvelope;
import com.conveyal.r5.analyst.cluster.TaskStatistics;
import com.conveyal.r5.common.JsonUtilities;
import com.conveyal.r5.profile.PropagatedTimesStore;
import com.conveyal.r5.profile.RaptorWorker;
import com.conveyal.r5.profile.StreetMode;
import com.conveyal.r5.streets.LinkedPointSet;
import com.conveyal.r5.streets.PointSetTimes;
import com.conveyal.r5.streets.StreetRouter;
import com.conveyal.r5.transit.TransportNetwork;
import com.conveyal.r5.transit.TransportNetworkCache;
import com.fasterxml.jackson.core.JsonEncoding;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.google.common.io.Files;
import gnu.trove.map.TIntIntMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.GZIPOutputStream;

/**
 * An AWS Lambda Analyst worker. Returns GeoJSON isochrones.
 */
public class LambdaWorker implements RequestStreamHandler {
    private static final Logger LOG = LoggerFactory.getLogger(LambdaWorker.class);

    /** single static cache, JVM scoped, re-use transport networks between lambda calls when possible */
    private static LambdaNetworkCache networkCache = new LambdaNetworkCache();

    @Override
    public void handleRequest(InputStream inputStream, OutputStream outputStream, Context context) throws IOException {
        LambdaClusterRequest request = JsonUtilities.objectMapper.readValue(inputStream, LambdaClusterRequest.class);

        switch (request.resultType) {
            case GEOJSON_ISOCHRONES:
                handleIsochrones(request, outputStream, context);
                break;
            case TAUI:
                LOG.error("TAUI format files are not currently supported in the Lambda worker.");
        }
    }

    /** Create isochrones from a request */
    private void handleIsochrones (LambdaClusterRequest request, OutputStream outputStream, Context context) {
        // TODO make sure network exists.
        TransportNetwork network =
                networkCache.getNetworkForScenario(request.networkBucket, request.networkId, request.scenario);

        // should be cached if network has been used
        LinkedPointSet targets = network.getLinkedGridPointSet();

        // find accessible transit stops
        StreetRouter sr = new StreetRouter(network.streetLayer);
        sr.setOrigin(request.fromLat, request.fromLon);

        // TODO bike, drive, bikeshare, unicycle to transit
        sr.distanceLimitMeters = (int) (request.maxWalkTime * request.walkSpeed * 60);
        sr.streetMode = StreetMode.WALK;

        sr.route();
        TIntIntMap accessTimes = sr.getReachedStops();

        LOG.info("Found {} initial transit stops", accessTimes.size());

        // TODO this is wrong when there is a turn restriction at the destination, see #155
        // we need to use the code in streetrouter that gets the travel time to a sample.
        PointSetTimes nonTransitTimes = targets.eval(sr::getTravelTimeToVertex);

        RaptorWorker worker = new RaptorWorker(network.transitLayer, targets, request);
        PropagatedTimesStore pts = worker.runRaptor(accessTimes, nonTransitTimes, new TaskStatistics());
        ResultEnvelope re = pts.makeResults(targets.pointSet, false, false, true);

        JsonFactory jf = new JsonFactory();

        try {
            OutputStream wrappedOutputStream = new GZIPOutputStream(outputStream);

            JsonGenerator jgen = jf.createGenerator(wrappedOutputStream, JsonEncoding.UTF8);

            jgen.writeStartObject();

            // TODO don't hardwire average
            re.avgCase.writeIsochrones(jgen);

            jgen.writeEndObject();
            jgen.close();

            wrappedOutputStream.close();
        } catch (IOException e) {
            LOG.error("IO exception writing isochrones", e);
        }
    }
}
