package com.conveyal.r5.point_to_point;

import com.conveyal.r5.common.GeometryUtils;
import com.conveyal.r5.common.JsonUtilities;
import com.conveyal.r5.point_to_point.builder.RouterInfo;
import com.conveyal.r5.profile.Mode;
import com.conveyal.r5.profile.ProfileRequest;
import com.conveyal.r5.streets.EdgeStore;
import com.conveyal.r5.streets.Split;
import com.conveyal.r5.streets.StreetRouter;
import com.conveyal.r5.streets.VertexStore;
import com.conveyal.r5.transit.TransportNetwork;
import com.vividsolutions.jts.geom.Coordinate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.*;

import java.io.File;

import static spark.Spark.*;

/**
 * This will represent point to point searche server.
 *
 * It can build point to point TransportNetwork and start a server with API for point to point searches
 *
 */
public class PointToPointRouterServer {
    private static final Logger LOG = LoggerFactory.getLogger(PointToPointRouterServer.class);

    private static final int DEFAULT_PORT = 8080;

    private static final String DEFAULT_BIND_ADDRESS = "0.0.0.0";

    public static final String BUILDER_CONFIG_FILENAME = "build-config.json";

    private static final String USAGE = "It expects --build [path to directory with GTFS and PBF files] to build the graphs\nor --graphs [path to directory with graph] to start the server with provided graph";

    public static final int RADIUS_METERS = 200;

    public static void main(String[] commandArguments) {

        LOG.info("Arguments: {}", Arrays.toString(commandArguments));

        final boolean inMemory = false;

        if ("--build".equals(commandArguments[0])) {

            File dir = new File(commandArguments[1]);

            if (!dir.isDirectory() && dir.canRead()) {
                LOG.error("'{}' is not a readable directory.", dir);
            }

            TransportNetwork transportNetwork = TransportNetwork.fromDirectory(dir);
            //In memory doesn't save it to disk others do (build, preFlight)
            if (!inMemory) {
                try {
                    OutputStream outputStream = new BufferedOutputStream(
                        new FileOutputStream(new File(dir, "network.dat")));
                    transportNetwork.write(outputStream);
                    outputStream.close();
                } catch (IOException e) {
                    LOG.error("An error occurred during saving transit networks. Exiting.", e);
                    System.exit(-1);
                }

            }
        } else if ("--graphs".equals(commandArguments[0])) {
            File dir = new File(commandArguments[1]);

            if (!dir.isDirectory() && dir.canRead()) {
                LOG.error("'{}' is not a readable directory.", dir);
            }
            try {
                LOG.info("Loading transit networks from: {}", dir);
                InputStream inputStream = new BufferedInputStream(new FileInputStream(new File(dir, "network.dat")));
                TransportNetwork transportNetwork = TransportNetwork.read(inputStream);
                run(transportNetwork);
                inputStream.close();
            } catch (IOException e) {
                LOG.error("An error occurred during reading of transit networks", e);
                System.exit(-1);
            } catch (Exception e) {
                LOG.error("An error occurred during decoding of transit networks", e);
                System.exit(-1);
            }
        } else if ("--help".equals(commandArguments[0])
                || "-h".equals(commandArguments[0])
                || "--usage".equals(commandArguments[0])
                || "-u".equals(commandArguments[0])) {
            System.out.println(USAGE);
        } else {
            LOG.info("Unknown argument: {}", commandArguments[0]);
            System.out.println(USAGE);
        }

    }

    private static void run(TransportNetwork transportNetwork) {
        port(DEFAULT_PORT);
        staticFileLocation("debug-plan");
        // add cors header
        before((req, res) -> res.header("Access-Control-Allow-Origin", "*"));

        get("/metadata", (request, response) -> {
            response.header("Content-Type", "application/json");
            RouterInfo routerInfo = new RouterInfo();
            routerInfo.envelope = transportNetwork.getEnvelope();
            return JsonUtilities.objectMapper.writeValueAsString(routerInfo);

        });

        get("/plan", (request, response) -> {
            response.header("Content-Type", "application/json");
            Map<String, Object> content = new HashMap<>();
            String queryMode = request.queryParams("mode");

            Mode mode = Mode.valueOf(queryMode);
            if (mode == null) {
                content.put("errors", "Mode is wrong");
            }
            Float fromLat = request.queryMap("fromLat").floatValue();
            Float toLat = request.queryMap("toLat").floatValue();

            Float fromLon = request.queryMap("fromLon").floatValue();
            Float toLon = request.queryMap("toLon").floatValue();

            //TODO errorchecks

            ProfileRequest profileRequest = new ProfileRequest();
            profileRequest.fromLat = fromLat;
            profileRequest.fromLon = fromLon;
            profileRequest.toLat = toLat;
            profileRequest.toLon = toLon;
            StreetRouter streetRouter = new StreetRouter(transportNetwork.streetLayer);

            streetRouter.mode = mode;
            //Split for end coordinate
            Split split = transportNetwork.streetLayer.findSplit(profileRequest.toLat, profileRequest.toLon,
                RADIUS_METERS);
            streetRouter.setOrigin(profileRequest.fromLat, profileRequest.fromLon);
            streetRouter.route();

            //Gets lowest weight state for end coordinate split
            StreetRouter.State lastState = streetRouter.getState(split);
            if (lastState != null) {

                LinkedList<Integer> edges = new LinkedList<>();

            /*
            * Starting from latest (time-wise) state, copy states to the head of a list in reverse
            * chronological order. List indices will thus increase forward in time, and backEdges will
            * be chronologically 'back' relative to their state.
            */
                for (StreetRouter.State cur = lastState; cur != null; cur = cur.backState) {
                    //states.addFirst(cur);
                    if (cur.backEdge != -1 && cur.backState != null) {
                        edges.addFirst(cur.backEdge);
                    }
                }


                //Creates geometry currently only uses first and last point
                List<Coordinate> coordinateList = new ArrayList<>();
                for (Integer edgeIdx: edges) {

                    EdgeStore.Edge edge = transportNetwork.streetLayer.edgeStore.getCursor(edgeIdx);
                    VertexStore.Vertex fromVertex = transportNetwork.streetLayer.vertexStore.getCursor(edge.getFromVertex());
                    coordinateList.add(new Coordinate(fromVertex.getLon(), fromVertex.getLat()));

                }
                //we only save end vertex from the last edge otherwise we have duplicated points since end vertex from each edge is start vertex from the next
                EdgeStore.Edge edge = transportNetwork.streetLayer.edgeStore.getCursor(edges.getLast());
                VertexStore.Vertex toVertex = transportNetwork.streetLayer.vertexStore.getCursor(edge.getToVertex());
                coordinateList.add(new Coordinate(toVertex.getLon(), toVertex.getLat()));

                Coordinate[] coordinates = coordinateList.toArray(new Coordinate[coordinateList.size()]);
                //TODO: return this as geojson Feature since we can save some information inside properties
                content.put("data", GeometryUtils.geometryFactory.createLineString(coordinates));

            } else {
                content.put("errors", "Path to end coordinate wasn't found!");
            }

            return JsonUtilities.objectMapper.writeValueAsString(content);
        });

    }

}
