package com.conveyal.r5.publish;
import com.conveyal.r5.common.JsonUtilities;
import com.conveyal.r5.transit.TransportNetwork;
import com.conveyal.r5.transit.TransportNetworkCache;
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
    private static StaticSiteRequest staticSiteRequest;
    private static TransportNetwork network;

    public static void main (String... args) throws Exception {
        // read config
        File in = new File(args[0]);
        staticSiteRequest = JsonUtilities.objectMapper.readValue(in, StaticSiteRequest.class);

        // read network
        TransportNetworkCache cache = new TransportNetworkCache(args[1]);
        network = cache.getNetwork(staticSiteRequest.transportNetworkId);

        // precompute metadata and stop tree
        ByteArrayOutputStream mbaos = new ByteArrayOutputStream();
        StaticMetadata sm = new StaticMetadata(staticSiteRequest, network);
        sm.writeMetadata(mbaos);
        metadata = mbaos.toByteArray();

        ByteArrayOutputStream sbaos = new ByteArrayOutputStream();
        sm.writeStopTrees(sbaos);
        stopTrees = sbaos.toByteArray();

        // add cors header
        before((req, res) -> res.header("Access-Control-Allow-Origin", "*"));

        get("/query.json", StaticServer::getQuery);
        get("/stop_trees.dat", StaticServer::getStopTrees);
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

    public static byte[] getOrigin (Request req, Response res) {
        res.header("Content-Type", "application/octet-stream");
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        int x = Integer.parseInt(req.params("x"));
        int y = Integer.parseInt(req.params("y").replaceFirst("\\.dat$", ""));
        StaticSiteRequest.PointRequest pr = staticSiteRequest.getPointRequest(x, y);
        StaticComputer computer = new StaticComputer(pr, network, ts -> {});
        try {
            computer.write(baos);
            return baos.toByteArray();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
