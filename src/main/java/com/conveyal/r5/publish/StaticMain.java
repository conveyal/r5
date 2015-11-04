package com.conveyal.r5.publish;

import com.conveyal.r5.analyst.WebMercatorGridPointSet;
import com.conveyal.r5.common.JsonUtilities;
import com.conveyal.r5.streets.LinkedPointSet;
import com.conveyal.r5.transit.TransportNetwork;
import com.conveyal.r5.transit.TransportNetworkCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Main class to compute a static site based on configuration JSON.
 *
 * Usage: ... request.json graph-bucket
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


        LOG.info("Performing requests");
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

        requests.parallelStream().forEach(pr -> {
            new StaticComputer(pr, net, ts -> {}).run();
        });

        LOG.info("done");
    }
}
