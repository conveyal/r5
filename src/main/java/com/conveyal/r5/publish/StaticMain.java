package com.conveyal.r5.publish;

import com.conveyal.r5.analyst.PointSet;
import com.conveyal.r5.analyst.WebMercatorGridPointSet;
import com.conveyal.r5.analyst.cluster.GenericClusterRequest;
import com.conveyal.r5.common.JsonUtilities;
import com.conveyal.r5.profile.StreetMode;
import com.conveyal.r5.streets.LinkedPointSet;
import com.conveyal.r5.transit.TransportNetwork;
import com.conveyal.r5.transit.TransportNetworkCache;
import com.conveyal.r5.util.S3Util;
import com.google.common.io.ByteStreams;
import com.google.common.io.Files;
import com.vividsolutions.jts.geom.Envelope;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.HttpClients;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.conveyal.r5.analyst.Grid.latToPixel;
import static com.conveyal.r5.analyst.Grid.lonToPixel;

/**
 * Main class to compute a static site based on configuration JSON.
 *
 * Usage: ... request.json graph-bucket broker-url
 */
public class StaticMain {
    private static final Logger LOG = LoggerFactory.getLogger(StaticMain.class);

    public static void main (String... args) throws Exception {
        File in = new File(args[0]);
        StaticSiteRequest ssr = JsonUtilities.objectMapper.readValue(in, StaticSiteRequest.class);

        // Store the scenario on S3 so it is not duplicated in every request
        if (ssr.request.scenario.modifications != null && !ssr.request.scenario.modifications.isEmpty()) {
            LOG.info("Storing scenario on S3");
            String scenarioId = ssr.request.scenario.id != null
                    ? ssr.request.scenario.id
                    : UUID.randomUUID().toString();
            String fileName = String.format("%s_%s.json", ssr.transportNetworkId, scenarioId);
            File scenarioFile = File.createTempFile("scenario", ".json");
            JsonUtilities.objectMapper.writeValue(scenarioFile, ssr.request.scenario);
            S3Util.s3.putObject(args[1], fileName, scenarioFile);
            scenarioFile.delete();
            ssr.request.scenario = null;
            ssr.request.scenarioId = scenarioId;
        }

        LOG.info("Finding transport network.");
        // use temporary directory so as not to pollute a shared cache
        TransportNetworkCache cache = new TransportNetworkCache(args[1], Files.createTempDir());
        TransportNetwork net = cache.getNetworkForScenario(ssr.transportNetworkId, ssr.request);

        WebMercatorGridPointSet ps = net.gridPointSet;
        LinkedPointSet lps = ps.link(net.streetLayer, StreetMode.WALK);

        LOG.info("Enqueueing requests");

        List<GenericClusterRequest> requests = new ArrayList<>();

        int east, west, north, south;

        if (!Double.isNaN(ssr.east)) {
            east = lonToPixel(ssr.east, ps.zoom);
            west = lonToPixel(ssr.west, ps.zoom);
            north = latToPixel(ssr.north, ps.zoom);
            south = latToPixel(ssr.south, ps.zoom);
        } else {
            west = ps.west;
            north = ps.north;
            south = ps.north + ps.height;
            east = ps.west + ps.width;
        }
        int width = east - west;
        int height = south - north;

        LOG.info("Width: {}, height: {}", width, height);

        // now clear the bounds of the staticsiterequest so they are not serialized, to maintain backwards compatibility
        // with older brokers/workers
        ssr.west = ssr.east = ssr.north = ssr.south = Double.NaN;

        // create the metadata request
        StaticMetadata.MetadataRequest metadataRequest = new StaticMetadata.MetadataRequest();
        metadataRequest.request = ssr;
        metadataRequest.workerVersion = ssr.workerVersion;
        metadataRequest.graphId = ssr.transportNetworkId;
        metadataRequest.jobId = ssr.jobId;
        requests.add(metadataRequest);

        StaticMetadata.StopTreeRequest stopTreeRequest = new StaticMetadata.StopTreeRequest();
        stopTreeRequest.request = ssr;
        stopTreeRequest.workerVersion = ssr.workerVersion;
        stopTreeRequest.graphId = ssr.transportNetworkId;
        stopTreeRequest.jobId = ssr.jobId;
        requests.add(stopTreeRequest);

        boolean requestIsNotFullyContainedWithinTransportNetwork = false;

        for (int queryX = 0; queryX < width; queryX++) {
            int networkX = queryX + west - ps.west;
            
            if (networkX < 0 || networkX >= ps.width) {
                requestIsNotFullyContainedWithinTransportNetwork = true;
                continue;
            }
            
            for (int queryY = 0; queryY < height; queryY++) {
                int networkY = queryY + north - ps.north;

                if (networkY < 0 || networkY >= ps.height) {
                    requestIsNotFullyContainedWithinTransportNetwork = true;
                    continue;
                }

                if (lps.edges[networkY * ps.width + networkX] == -1) continue; // unlinked

                requests.add(ssr.getPointRequest(networkX, networkY));
            }
        }

        if (requestIsNotFullyContainedWithinTransportNetwork) {
            LOG.warn("Extents of static site request were not completely contained within transport network.");
        }

        int nRequests = requests.size();

        // enqueue 50000 requests at a time so Jackson doesn't run out of memory
        for (int offset = 0; offset < nRequests; offset += 50000) {
            try {
                HttpClient httpClient = HttpClients.createDefault();
                HttpPost request = new HttpPost(args[2] + "/enqueue/regional");
                request.setHeader("Content-Type", "application/json");
                List<GenericClusterRequest> subRequests = requests.subList(offset, Math.min(offset + 50000, nRequests));
                request.setEntity(new StringEntity(JsonUtilities.objectMapper.writeValueAsString(subRequests)));
                HttpResponse res = httpClient.execute(request);

                if (res.getStatusLine().getStatusCode() != 200 && res.getStatusLine().getStatusCode() != 202) {
                    InputStream is = res.getEntity().getContent();
                    String responseText = new String(ByteStreams.toByteArray(is));
                    is.close();
                    LOG.error("Request was unsuccessful, retrying after 5s delay: {}\n{}", res.getStatusLine().toString(), responseText);

                    Thread.sleep(5000);
                    offset -= 50000; // will be incremented back to where it was at top of loop
                }
            } catch (Exception e) {
                LOG.error("Exception enqueueing, retrying", e);
                Thread.sleep(5000);
                offset -= 50000; // will be incremented back to where it was at top of loop
            }
        }

        LOG.info("{} requests enqueued successfully", requests.size());

        LOG.info("done");
    }
}
