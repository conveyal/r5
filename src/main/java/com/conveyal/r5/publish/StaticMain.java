package com.conveyal.r5.publish;

import com.conveyal.r5.analyst.WebMercatorGridPointSet;
import com.conveyal.r5.common.JsonUtilities;
import com.conveyal.r5.streets.LinkedPointSet;
import com.conveyal.r5.transit.TransportNetwork;
import com.conveyal.r5.transit.TransportNetworkCache;
import com.google.common.io.ByteStreams;
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

        LOG.info("Finding transport network.");
        TransportNetworkCache cache = new TransportNetworkCache(args[1]);
        TransportNetwork net = cache.getNetwork(ssr.transportNetworkId);

        LOG.info("Computing metadata");
        StaticMetadata metadata = new StaticMetadata(ssr, net);
        metadata.run();


        LOG.info("Enqueueing requests");
        List<StaticSiteRequest.PointRequest> requests = new ArrayList<>();

        WebMercatorGridPointSet ps = net.getGridPointSet();

        // pre-link so it doesn't get done in every thread
        LinkedPointSet lps = ps.link(net.streetLayer);

        for (int x = 0; x < ps.width; x++) {
            for (int y = 0; y < ps.height; y++) {

                if (lps.edges[y * (int) ps.width + x] == -1)
                    continue; // don't store unlinked points

                requests.add(ssr.getPointRequest(x, y));
            }
        }

        int nRequests = requests.size();

        // enqueue 50000 requests at a time so Jackson doesn't run out of memory
        for (int offset = 0; offset < nRequests; offset += 50000) {
            try {
                HttpClient httpClient = HttpClients.createDefault();
                HttpPost request = new HttpPost(args[2] + "/enqueue/jobs");
                request.setHeader("Content-Type", "application/json");
                List<StaticSiteRequest.PointRequest> subRequests = requests.subList(offset, Math.min(offset + 50000, nRequests));
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
