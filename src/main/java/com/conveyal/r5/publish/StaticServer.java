package com.conveyal.r5.publish;
import com.conveyal.r5.analyst.cluster.TaskStatistics;
import com.conveyal.r5.common.JsonUtilities;
import com.conveyal.r5.transit.TransportNetwork;
import com.conveyal.r5.transit.TransportNetworkCache;
import com.conveyal.r5.transitive.TransitiveNetwork;
import spark.Request;
import spark.Response;

import java.io.ByteArrayOutputStream;
import java.io.File;

import static spark.Spark.*;

/**
 * Serve up "static site" output live.
 */
public class StaticServer {
    private static byte[] metadata;
    private static byte[] stopTrees;
    private static byte[] transitive;
    private static StaticSiteRequest staticSiteRequest;
    private static TransportNetwork network;

    public static void main (String... args) throws Exception {
        // read config
        File in = new File(args[0]);
        staticSiteRequest = JsonUtilities.objectMapper.readValue(in, StaticSiteRequest.class);

        // read network
        File cacheDir = new File("cache");
        if (!cacheDir.exists()) cacheDir.mkdir();
        TransportNetworkCache cache = new TransportNetworkCache(args[1], cacheDir);
        if (staticSiteRequest.request.scenario != null || staticSiteRequest.request.scenarioId != null) {
            network = cache.getNetworkForScenario(staticSiteRequest.transportNetworkId, staticSiteRequest.request);
        }
        else {
            network = cache.getNetwork(staticSiteRequest.transportNetworkId);
        }

        // precompute metadata and stop tree
        ByteArrayOutputStream mbaos = new ByteArrayOutputStream();
        StaticMetadata sm = new StaticMetadata(staticSiteRequest, network);
        sm.writeMetadata(mbaos);
        metadata = mbaos.toByteArray();

        ByteArrayOutputStream sbaos = new ByteArrayOutputStream();
        sm.writeStopTrees(sbaos);
        stopTrees = sbaos.toByteArray();

        TransitiveNetwork tn = new TransitiveNetwork(network.transitLayer, network.streetLayer);
        ByteArrayOutputStream tnbaos = new ByteArrayOutputStream();
        JsonUtilities.objectMapper.writeValue(tnbaos, tn);
        transitive = tnbaos.toByteArray();

        // optionally allow specifying port
        if (args.length == 3) {
            port(Integer.parseInt(args[2]));
        }

        // add cors header
        before((req, res) -> res.header("Access-Control-Allow-Origin", "*"));

        get("/query.json", StaticServer::getQuery);
        get("/stop_trees.dat", StaticServer::getStopTrees);
        get("/transitive.json", StaticServer::getTransitiveNetwork);
        get("/:x/:y", StaticServer::getOrigin);
    }

    /** get a query json file */
    public static byte[] getQuery (Request req, Response res) {
        res.header("Content-Type", "application/json");
        return metadata;
    }

    public static byte[] getStopTrees (Request req, Response res) {
        res.header("Content-Type", "application/octet-stream");
        return stopTrees;
    }

    public static byte[] getTransitiveNetwork (Request req, Response res) {
        res.header("Content-Type", "application/json");
        return transitive;
    }

    public static byte[] getOrigin (Request req, Response res) {
        res.header("Content-Type", "application/octet-stream");
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        int x = Integer.parseInt(req.params("x"));
        int y = Integer.parseInt(req.params("y").replaceFirst("\\.dat$", ""));
        StaticSiteRequest.PointRequest pr = staticSiteRequest.getPointRequest(x, y);
        StaticComputer computer = new StaticComputer(pr, network, new TaskStatistics());
        try {
            computer.write(baos);
            return baos.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
