package com.conveyal.r5.integration;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.conveyal.r5.analyst.PointSet;
import com.conveyal.r5.analyst.PointSetCache;
import com.conveyal.r5.analyst.cluster.AnalystClusterRequest;
import com.conveyal.r5.analyst.cluster.PointSetDatastore;
import com.conveyal.r5.analyst.cluster.ResultEnvelope;
import com.conveyal.r5.analyst.cluster.TaskStatistics;
import com.conveyal.r5.common.JsonUtilities;
import com.conveyal.r5.profile.McRaptorSuboptimalPathProfileRouter;
import com.conveyal.r5.profile.PropagatedTimesStore;
import com.conveyal.r5.profile.RaptorWorker;
import com.conveyal.r5.profile.RepeatedRaptorProfileRouter;
import com.conveyal.r5.profile.StreetMode;
import com.conveyal.r5.streets.LinkedPointSet;
import com.conveyal.r5.streets.StreetRouter;
import com.conveyal.r5.transit.TransportNetwork;
import com.conveyal.r5.transit.TransportNetworkCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;

/**
 * Perform an evaluation of the statistical properties of either the RAPTOR or McRAPTOR router. Designed to be run as an
 * AWS Lambda function.
 */
public class ErrorDiagnostics implements RequestHandler<DiagnosticsRequest, Boolean> {
    private static final Logger LOG = LoggerFactory.getLogger(ErrorDiagnostics.class);

    private static final AmazonS3 s3 = new AmazonS3Client();

    private TransportNetworkCache networkCache;
    private PointSetCache pointSetCache;

    @Override
    public Boolean handleRequest(DiagnosticsRequest req, Context context) {
        // download the transport network
        networkCache = new TransportNetworkCache(req.networkBucket);
        TransportNetwork network = networkCache.getNetwork(req.networkId);

        // and the pointset
        pointSetCache = new PointSetDatastore(1, null, false, req.pointsetBucket);
        PointSet pset = pointSetCache.get(req.pointsetId);

        LinkedPointSet lps = pset.link(network.streetLayer, StreetMode.WALK);

        AnalystClusterRequest acr = new AnalystClusterRequest();
        acr.profileRequest = req.request;
        acr.graphId = req.networkId;
        acr.destinationPointsetId = req.pointsetId;
        acr.includeTimes = false;
        acr.outputLocation = null;

        if (req.spectrograph) {
            if (req.request.maxFare < 0) {
                StreetRouter sr = new StreetRouter(network.streetLayer);
                sr.distanceLimitMeters = 2000;
                sr.setOrigin(req.request.fromLat, req.request.fromLon);
                sr.route();

                RaptorWorker worker = new RaptorWorker(network.transitLayer, lps, req.request);
                worker.runRaptor(sr.getReachedStops(), lps.eval(sr::getTravelTimeToVertex), new TaskStatistics());

                // we now have the times at each point at each iteration. We use a propagated times store to get the
                // accessibility at each iteration
                // TODO exclude iterations that should not be included in averaged using worker.includeInAverages
                req.results = new ArrayList<>();

                for (int iteration = 0; iteration < worker.timesAtTargetsEachIteration.length; iteration++) {
                    if (!worker.includeInAverages.get(iteration)) continue;

                    int[] times = worker.timesAtTargetsEachIteration[iteration];

                    PropagatedTimesStore pts = new PropagatedTimesStore(times.length);
                    pts.setFromArray(new int[][]{times}, req.request.reachabilityThreshold);
                    req.results.add(pts.makeResults(pset, false, true, false).avgCase.histograms.get(req.pointsetField).sums);
                }
            } else {
                // Fare based McRAPTOR routing
                McRaptorSuboptimalPathProfileRouter router = new McRaptorSuboptimalPathProfileRouter(network, acr, lps);
                router.NUMBER_OF_SEARCHES = req.samples;
                router.route();

                req.results = new ArrayList<>();

                for (int[] times : router.timesAtTargetsEachIteration) {
                    // everything is included in averages, we are not calculating bounds
                    PropagatedTimesStore pts = new PropagatedTimesStore(times.length);
                    pts.setFromArray(new int[][]{times}, req.request.reachabilityThreshold);
                    req.results.add(pts.makeResults(pset, false, true, false).avgCase.histograms.get(req.pointsetField).sums);
                }
            }
        } else {
            req.results = IntStream.range(0, req.samples)
                    .parallel()
                    .mapToObj(sample -> {
                        // TODO handle McRAPTOR here
                        RepeatedRaptorProfileRouter rrpr = new RepeatedRaptorProfileRouter(network, acr, lps, new TaskStatistics());
                        ResultEnvelope env = rrpr.route();
                        return env.avgCase.histograms.get(req.pointsetField).sums;
                    })
                    .collect(Collectors.toList());
        }


        try {
            File file = File.createTempFile("result", ".json.gz");

            JsonUtilities.objectMapper.writeValue(file, req);

            ObjectMetadata om = new ObjectMetadata();
            om.setContentType("application/json");

            // dump data file out to S3 for safekeeping
            PutObjectRequest por = new PutObjectRequest(
                    req.outputBucket,
                    String.format("%s/%s.json", req.testId, req.id),
                    file
            )
                    .withMetadata(om);

            // run S3 put in main thread so Lambda lives until results are written
            s3.putObject(por);

            file.delete();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return true;
    }

    public static void main (String... args) {
        DiagnosticsRequest req;
        try {
            req = JsonUtilities.objectMapper.readValue(new File(args[0]), DiagnosticsRequest.class);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        new ErrorDiagnostics().handleRequest(req, null);
    }
}
