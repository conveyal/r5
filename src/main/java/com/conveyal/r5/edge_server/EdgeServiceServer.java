package com.conveyal.r5.edge_server;

import com.conveyal.osmlib.Way;
import com.conveyal.r5.streets.EdgeStore.Edge;
import com.conveyal.r5.streets.EdgeStore;
import com.conveyal.r5.streets.VertexStore;
import com.conveyal.r5.transit.TransportNetwork;

import java.util.*;
import java.io.File;
import java.io.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.conveyal.r5.streets.EdgeStore.EdgeFlag.ALLOWS_CAR;

/**
 * This will represent Edge Service server.
 *
 * It can build point to point TransportNetwork and start a server with API for edge lookups.
 * Format
 * --build flag produces network.dat.
 * --write_edges produces street_edges.csv, a CSV of all edge ids with start and end lat/lngs.
 *
 */
public class EdgeServiceServer {
    private static final Logger LOG = LoggerFactory.getLogger(EdgeServiceServer.class);

    private static final String USAGE = "It expects --build [path to directory with GTFS and PBF files] to build the graphs\nor --graphs [path to directory with graph] to start the server with provided graph.\nTo use the existing network.dat file and print street edges to CSV, use --build [path to directory with GTFS and PBF files] --existing";


    public static void main(String[] commandArguments) {

        LOG.info("Arguments: {}", Arrays.toString(commandArguments));

        File dir = new File(commandArguments[1]);
        
        if (!dir.isDirectory() && dir.canRead()) {
            LOG.error("'{}' is not a readable directory.", dir);
        }
        // Build edge network
        if ("--build".equals(commandArguments[0])) {
            TransportNetwork transportNetwork = null;
            LOG.info("Building new transport network and writing to network.dat");
            try {
                transportNetwork = TransportNetwork.fromDirectory(dir);
                transportNetwork.write(new File(dir, "network.dat")); 
            } catch (Exception ex) {
                LOG.error("An error occurred during saving transit networks. Exiting.", ex);
                System.exit(-1);
            }
        }
        // Write street edges to CSV for use in post processing and evaluation.
        else if ("--write_edges".equals(commandArguments[0])) {
            TransportNetwork transportNetwork = null;
            LOG.info("Attempting to read transport network from file...");
            try {
                transportNetwork = TransportNetwork.read(new File(dir, "network.dat"));
                transportNetwork.readOSM(new File(dir, "osm.mapdb"));
            } catch (Exception e) {
                LOG.error("An error occurred during the reading or decoding of transport networks", e);
                System.exit(-1);
            }

            LOG.info("Transport network created. Starting write to street_edges.csv...");
            try {
                writeStreetEdgesToCSV(transportNetwork, new File(dir, "street_edges.csv"));
            } catch (Exception e) {
                LOG.error("An error occurred during writing street edges to csv. Exiting.", e);
                System.exit(-1);
            }
        } else if ("--write_vertices".equals(commandArguments[0])) {
            TransportNetwork transportNetwork = null;
            LOG.info("Attempting to read transport network from file...");
            try {
                transportNetwork = TransportNetwork.read(new File(dir, "network.dat"));
                transportNetwork.readOSM(new File(dir, "osm.mapdb"));
            } catch (Exception e) {
                LOG.error("An error occurred during the reading or decoding of transport networks", e);
                System.exit(-1);
            }

            LOG.info("Transport network created. Starting write to street_edges.csv...");
            try {
                writeVerticesToCSV(transportNetwork, new File(dir, "vertices.csv"));
            } catch (Exception e) {
                LOG.error("An error occurred during writing street edges to csv. Exiting.", e);
                System.exit(-1);
            }
            // Start server to serve edges
        } else if ("--serve".equals(commandArguments[0])) {
            try {
                LOG.info("Loading transit networks from: {}", dir);
                TransportNetwork transportNetwork = TransportNetwork.read(new File(dir, "network.dat"));
                transportNetwork.readOSM(new File(dir, "osm.mapdb"));
                run(transportNetwork);
            } catch (Exception e) {
                LOG.error("An error occurred during the reading or decoding of transit networks", e);
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

    private static void writeVerticesToCSV(TransportNetwork transportNetwork, File file) {
        LOG.info("Writing vertices...");
        OutputStream outputStream;
        try {
            outputStream = new BufferedOutputStream(new FileOutputStream(file));
        } catch (FileNotFoundException e) {
            throw new RuntimeException(e);
        }
        PrintStream printStream = new PrintStream(outputStream);
        printStream.println("\"vertexId\",\"lat\",\"lon\",\"flags\",\"inEdges\",\"outEdges\"");

        int vertexCount = transportNetwork.streetLayer.vertexStore.getVertexCount();
        for (int v = 0; v < vertexCount; v++) {
            VertexStore.Vertex vertex = transportNetwork.streetLayer.vertexStore.getCursor(v);
            printStream.printf("%d,%f,%f,\"%s\",\"%s\",\"%s\"\n",
                    vertex.index, vertex.getLat(), vertex.getLon(), vertex.getFlagsAsString(),
                    transportNetwork.streetLayer.incomingEdges.get(vertex.index), transportNetwork.streetLayer.outgoingEdges.get(vertex.index));
        }
        printStream.close();
        LOG.info("Done writing.");
    }

    /**
     * This is used by the EdgeServiceServer to generate a CSV representation of the entire street
     * network for a given city. This CSV is of the format
     * streetEdgeId, fromLat, fromLong, toLat, toLong
     * It is used for evaluation and post-processing tasks.
     */
    private static void writeStreetEdgesToCSV (TransportNetwork transportNetwork, File file) throws IOException {
        LOG.info("Writing street edges...");

        PrintStream printStream = null;

        OutputStream outputStream;
        try {
            outputStream = new BufferedOutputStream(new FileOutputStream(file));
        } catch (FileNotFoundException e) {
            throw new RuntimeException(e);
        }
        printStream = new PrintStream(outputStream);
        printStream.println("\"edgeId\",\"startVertex\",\"endVertex\",\"startLat\",\"startLon\",\"endLat\",\"endLon\",\"geometry\",\"streetName\",\"distance\",\"osmid\",\"speed\",\"flags\",\"lanes\",\"highway\",\"lanesTag\",\"lanesForwardTag\",\"lanesBackwardTag\"");

        EdgeStore edgeStore = transportNetwork.streetLayer.edgeStore;
        Edge forwardEdge = edgeStore.getCursor();
        Edge backwardEdge = edgeStore.getCursor();
        for (int e = 0; e < edgeStore.nEdges(); e+=2) {
            forwardEdge.seek(e);
            backwardEdge.seek(e+1);
            int overallLanes = parseLanesTag(transportNetwork, forwardEdge, "lanes");
            int forwardLanes = parseLanesTag(transportNetwork, forwardEdge, "lanes:forward");
            int backwardLanes = parseLanesTag(transportNetwork, backwardEdge, "lanes:backward");

            if (!forwardEdge.getFlag(ALLOWS_CAR)) {
                forwardLanes = 0;
            }

            if (!backwardEdge.getFlag(ALLOWS_CAR)) {
                backwardLanes = 0;
            }

            if (forwardLanes == -1) {
                if (overallLanes != -1) {
                    if (backwardLanes != -1) {
                        forwardLanes = overallLanes - backwardLanes;
                    } else if (forwardEdge.getFlag(ALLOWS_CAR)) {
                        forwardLanes = overallLanes / 2;
                    }
                }
            }

            if (backwardLanes == -1) {
                if (overallLanes != -1) {
                    if (forwardLanes != -1) {
                        backwardLanes = overallLanes - forwardLanes;
                    }
                }
            }

            // StreetEdgeInfo's edgeID gets populated using Edge.EdgeIndex, so they are equivalent here.
            printStream.println(toString(transportNetwork, forwardEdge, forwardLanes));
            printStream.println(toString(transportNetwork, backwardEdge, backwardLanes));
        }
        printStream.close();
        LOG.info("Done writing.");
    }

    private static String toString(TransportNetwork transportNetwork, Edge edge, int directionalLanes) {
        return String.format("%d,%d,%d,%f,%f,%f,%f,\"%s\",\"%s\",%d,%d,%d,\"%s\",%d,\"%s\",\"%s\",\"%s\",\"%s\"",
                    edge.getEdgeIndex(), edge.getFromVertex(), edge.getToVertex(), edge.getFromCoordinate().x, edge.getFromCoordinate().y,
                    edge.getToCoordinate().x, edge.getToCoordinate().y, edge.getGeometry(),
                    transportNetwork.streetLayer.getNameEdgeIdx(edge.getEdgeIndex(), Locale.ENGLISH),
                    edge.getLengthMm(), edge.getOSMID(), edge.getSpeed(), edge.getFlags(),
                    directionalLanes,
                    getStringTagOrEmpty(transportNetwork, edge, "highway"),
                    getStringTagOrEmpty(transportNetwork, edge, "lanes"),
                    getStringTagOrEmpty(transportNetwork, edge, "lanes:forward"),
                    getStringTagOrEmpty(transportNetwork, edge, "lanes:backward")
                );
    }

    private static String getStringTagOrEmpty(TransportNetwork transportNetwork, Edge edge, String key) {
        Way way = transportNetwork.streetLayer.osm.ways.get(edge.getOSMID());
        if (way != null) {
            if (way.hasTag(key)) {
                return way.getTag(key);
            } else {
                return "";
            }
        } else {
            return "";
        }
    }

    private static int parseLanesTag(TransportNetwork transportNetwork, Edge edge, String key) {
        int result = -1;
        Way way = transportNetwork.streetLayer.osm.ways.get(edge.getOSMID());
        if (way != null) {
            if (way.hasTag(key)) {
                String tagValue = null;
                try {
                    tagValue = way.getTag(key);
                    return parseLanesTag(tagValue);
                } catch (NumberFormatException ex) {
                    LOG.warn("way {}: Unable to parse {} value as number {}", edge.getOSMID(), key, tagValue);
                }
            }
        }
        return result;
    }

    static int parseLanesTag(String tagValue) {
        double[] values = Arrays.stream(tagValue.split(";"))
                .mapToDouble(Double::parseDouble)
                .toArray();
        Arrays.sort(values);
        double median;
        if (values.length % 2 == 0) {
            median = values[values.length/2-1];
        } else {
            median = values[values.length / 2];
        }
        return (int) median;
    }

    private static void run(TransportNetwork transportNetwork) {
        // TODO(samara or michael): Implement a server that allows queries by edgeID for full edge information.
    }

}