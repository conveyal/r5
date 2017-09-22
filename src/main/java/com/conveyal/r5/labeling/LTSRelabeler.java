package com.conveyal.r5.labeling;

import com.conveyal.r5.api.util.LegMode;
import com.conveyal.r5.profile.StreetMode;
import com.conveyal.r5.streets.EdgeStore;
import com.conveyal.r5.streets.StreetRouter;
import com.conveyal.r5.streets.VertexStore;
import com.conveyal.r5.transit.TransportNetwork;
import gnu.trove.list.TIntList;
import gnu.trove.list.array.TIntArrayList;
import org.apache.commons.math3.random.MersenneTwister;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.EnumSet;
import java.util.stream.IntStream;

/**
 * Test the sensitivity of the LTS model by randomly choosing vertices and relabeling the streets that come into them,
 * flipping them from LTS 3 to 2.
 */
public class LTSRelabeler {
    private static final Logger LOG = LoggerFactory.getLogger(LTSRelabeler.class);

    public static final int RELABEL_ITERATIONS = 2_000;
    public static final int SEARCHES_PER_ITERATION = 1_000;

    public TransportNetwork network;
    private TIntList originalFlags;

    public LTSRelabeler(TransportNetwork network) {
        this.network = network;
        this.originalFlags = network.streetLayer.edgeStore.flags;
    }

    public int relabel (int vertex) {
        // make a fresh copy of the street layer's flags since we modify them below
        network.streetLayer.edgeStore.flags = new TIntArrayList(originalFlags);
        int relabeledEdges = 0;

        // walk, relabeling all contiguous edges from LTS 3 to LTS 2
        relabeledEdges = walk(vertex);

        return relabeledEdges;
    }

    /** undo any relabeling */
    public void restore () {
        network.streetLayer.edgeStore.flags = originalFlags;
    }

    public int walk (int vertex) {
        int relabeled = 0;
        EdgeStore.Edge e = network.streetLayer.edgeStore.getCursor();
        TIntList edgesToExplore = new TIntArrayList();

        edgesToExplore.addAll(network.streetLayer.outgoingEdges.get(vertex));

        while (!edgesToExplore.isEmpty() && relabeled < 200) {
            int eidx = edgesToExplore.removeAt(0);
            e.seek(eidx);

            if (e.getFlag(EdgeStore.EdgeFlag.BIKE_LTS_3)) {
                // relabel
                e.clearFlag(EdgeStore.EdgeFlag.BIKE_LTS_3);
                e.setFlag(EdgeStore.EdgeFlag.BIKE_LTS_2);
                relabeled++;

                // explore from this edge
                edgesToExplore.addAll(network.streetLayer.outgoingEdges.get(e.getToVertex()));
            }

            if (e.isForward()) e.advance();
            else e.retreat();

            // relabel back edge
            if (e.getFlag(EdgeStore.EdgeFlag.BIKE_LTS_3)) {
                e.clearFlag(EdgeStore.EdgeFlag.BIKE_LTS_3);
                e.setFlag(EdgeStore.EdgeFlag.BIKE_LTS_2);
                relabeled++;

                // don't explore from this edge as it brings us back where we started from
            }
        }

        return relabeled;
    }

    /** Usage: LTSRelabeler {network|directory} out.csv */
    public static void main (String... args) throws Exception {
        File netFile = new File(args[0]);
        TransportNetwork network;
        if (netFile.isDirectory())
            network = TransportNetwork.fromDirectory(netFile);
        else {
            network = TransportNetwork.read(netFile);
        }

        Writer writer = new FileWriter(args[1]);
        writer.write("iteration,origin,lat,lon,edges_relabeled,reachable_vertices_before,reachable_vertices_after\n");

        LTSRelabeler relabeler = new LTSRelabeler(network);

        MersenneTwister mt = new MersenneTwister();

        for (int relabel = 0; relabel < RELABEL_ITERATIONS; relabel++) {
            long start = System.currentTimeMillis();
            if (relabel % 100 == 0)
                LOG.info("{} / {} relabels", relabel, RELABEL_ITERATIONS);

            int[] origins = IntStream.range(0, SEARCHES_PER_ITERATION).map(i -> mt.nextInt(network.streetLayer.getVertexCount())).toArray();
            int[] before = new int[origins.length];
            int[] after = new int[origins.length];

            relabeler.restore();

            IntStream.range(0, SEARCHES_PER_ITERATION).parallel().forEach(search -> {
                StreetRouter router = new StreetRouter(network.streetLayer);
                router.profileRequest.bikeTrafficStress = 2;
                router.profileRequest.accessModes = router.profileRequest.egressModes = router.profileRequest.directModes = EnumSet.of(LegMode.BICYCLE);
                router.streetMode = StreetMode.BICYCLE;
                router.setOrigin(origins[search]);
                router.distanceLimitMeters = 10_000;

                router.route();

                before[search] = router.getReachedVertices().size();
            });

            // find a random vertex
            int vertex;
            int relabeledEdges;
            do {
                vertex = mt.nextInt(network.streetLayer.getVertexCount());
            } while ((relabeledEdges = relabeler.relabel(vertex)) == 0);

            IntStream.range(0, SEARCHES_PER_ITERATION).parallel().forEach(search -> {
                StreetRouter router = new StreetRouter(network.streetLayer);
                router.profileRequest.bikeTrafficStress = 2;
                router.profileRequest.accessModes = router.profileRequest.egressModes = router.profileRequest.directModes = EnumSet.of(LegMode.BICYCLE);
                router.streetMode = StreetMode.BICYCLE;
                router.setOrigin(origins[search]);
                router.distanceLimitMeters = 10_000;

                router.route();

                after[search] = router.getReachedVertices().size();
            });

            VertexStore.Vertex v = network.streetLayer.vertexStore.getCursor(vertex);

            for (int search = 0; search < SEARCHES_PER_ITERATION; search++) {
                writer.write(relabel + "," + origins[search] + "," + v.getLat() + "," + v.getLon() + "," + relabeledEdges + "," + before[search] + "," + after[search] + "\n");
            }

            LOG.info("Relabel {}, {}ms", relabel, System.currentTimeMillis() - start);
        }
    }
}
