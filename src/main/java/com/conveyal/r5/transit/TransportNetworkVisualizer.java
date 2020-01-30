package com.conveyal.r5.transit;

import com.conveyal.r5.common.GeoJsonFeature;
import com.conveyal.r5.common.JsonUtilities;
import com.conveyal.r5.kryo.KryoNetworkSerializer;
import com.conveyal.r5.point_to_point.builder.TNBuilderConfig;
import org.locationtech.jts.geom.Envelope;
import gnu.trove.set.TIntSet;
import org.glassfish.grizzly.http.server.*;
import org.glassfish.grizzly.http.util.HttpStatus;
import com.conveyal.r5.streets.EdgeStore;
import com.conveyal.r5.streets.VertexStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.BindException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.conveyal.r5.streets.VertexStore.floatingDegreesToFixed;

/**
 * Simple Web-based visualizer for transport networks.
 */
public class TransportNetworkVisualizer {
    private static final Logger LOG = LoggerFactory.getLogger(TransportNetworkVisualizer.class);

    public static final String INTERFACE = "0.0.0.0";
    public static final int PORT = 9007;

    public static void main (String... args) throws Exception {
        LOG.info("Starting transport network visualizer");

        TransportNetwork network;
        if (args.length == 1) {
            LOG.info("Reading serialized transport network");
            File in = new File(args[0]);
            network = KryoNetworkSerializer.read(in);
            LOG.info("Done reading serialized transport network");
        }
        else if (args.length == 2) {
            LOG.info("Building transport network");
            File directory = new File(args[0]).getParentFile();
            TNBuilderConfig builderConfig;
            if (directory.isDirectory()) {
                builderConfig = TransportNetwork.loadJson(new File(directory, TransportNetwork.BUILDER_CONFIG_FILENAME));
            } else {
                builderConfig = TNBuilderConfig.defaultConfig();
            }
            network = TransportNetwork.fromFiles(args[0], args[1], builderConfig);
            LOG.info("Done building transport network");
        }
        else {
            LOG.info("usage:");
            LOG.info(" TransportNetworkVisualizer serialized_transport_network");
            LOG.info(" TransportNetworkVisualizer osm_file gtfs_file");
            return;
        }

        network.streetLayer.indexStreets();

        // network has been built
        // Start HTTP server
        LOG.info("Starting server on {}:{}", INTERFACE, PORT);
        HttpServer server = new HttpServer();
        server.addListener(new NetworkListener("transport_network_visualizer", INTERFACE, PORT));
        server.getServerConfiguration().addHttpHandler(new TransportNetworkHandler(network), "/api/*");
        server.getServerConfiguration().addHttpHandler(new CLStaticHttpHandler(ClassLoader.getSystemClassLoader(), "/visualization/"));
        try {
            server.start();
            LOG.info("Network visualizer server running.");
            Thread.currentThread().join();
        } catch (BindException be) {
            LOG.error("Cannot bind to port {}. Is it already in use?", PORT);
        } catch (IOException ioe) {
            LOG.error("IO exception while starting server.");
        } catch (InterruptedException ie) {
            LOG.info("Interrupted, shutting down.");
        }
        server.shutdown();

    }

    public static class TransportNetworkHandler extends
            org.glassfish.grizzly.http.server.HttpHandler {

        private final TransportNetwork network;

        public TransportNetworkHandler(TransportNetwork network) {
            this.network = network;
        }

        @Override public void service(Request req, Response res) throws Exception {
            try {
                String layer = req.getPathInfo().substring(1);
                double north = Double.parseDouble(req.getParameter("n"));
                double south = Double.parseDouble(req.getParameter("s"));
                double east = Double.parseDouble(req.getParameter("e"));
                double west = Double.parseDouble(req.getParameter("w"));

                Envelope env = new Envelope(floatingDegreesToFixed(east), floatingDegreesToFixed(west),
                        floatingDegreesToFixed(south), floatingDegreesToFixed(north));

                if ("streetEdges".equals(layer)) {
                    TIntSet streets = network.streetLayer.findEdgesInEnvelope(env);

                    if (streets.size() > 10_000) {
                        LOG.warn("Refusing to include more than 10000 edges in result");
                        res.sendError(400, "Request area too large");
                        return;
                    }

                    // write geojson to response
                    Map<String, Object> featureCollection = new HashMap<>(2);
                    featureCollection.put("type", "FeatureCollection");
                    List<GeoJsonFeature> features = new ArrayList<>(streets.size());


                    EdgeStore.Edge cursor = network.streetLayer.edgeStore.getCursor();
                    VertexStore.Vertex vcursor = network.streetLayer.vertexStore.getCursor();

                    streets.forEach(s -> {
                        try {
                            cursor.seek(s);

                            GeoJsonFeature feature = new GeoJsonFeature(cursor.getGeometry());
                            feature.addProperty("permission", cursor.getPermissionsAsString());
                            feature.addProperty("edge_id", cursor.getEdgeIndex());
                            features.add(feature);
                            return true;
                        } catch (Exception e) {
                            throw new RuntimeException(e);
                        }
                    });

                    featureCollection.put("features", features);
                    String json = JsonUtilities.objectMapper.writeValueAsString(featureCollection);

                    res.setStatus(HttpStatus.OK_200);
                    res.setContentType("application/json");
                    res.setContentLength(json.length());
                    res.getWriter().write(json);
                }
            } catch (Exception e) {
                LOG.error("Error servicing request", e);
            }
        }
    }
}
