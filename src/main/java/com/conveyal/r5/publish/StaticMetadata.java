package com.conveyal.r5.publish;

import com.conveyal.r5.analyst.PointSet;
import com.conveyal.r5.analyst.WebMercatorGridPointSet;
import com.conveyal.r5.common.JsonUtilities;
import com.conveyal.r5.profile.ProfileRequest;
import com.conveyal.r5.streets.LinkedPointSet;
import com.conveyal.r5.transit.TransportNetwork;
import com.google.common.io.LittleEndianDataOutputStream;
import gnu.trove.list.TIntList;
import gnu.trove.list.array.TIntArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.io.Serializable;

/**
 * Create the per-query files for a static site request.
 */
public class StaticMetadata implements Runnable {
    private static final Logger LOG = LoggerFactory.getLogger(StaticMetadata.class);

    private StaticSiteRequest request;
    private TransportNetwork network;

    public StaticMetadata (StaticSiteRequest req, TransportNetwork network) {
        this.request = req;
        this.network = network;
    }

    @Override
    public void run () {
        // first generate and write out metadata

        try {
            OutputStream os = StaticDataStore.getOutputStream(request, "query.json", "application/json");
            writeMetadata(os);
            os.close();
        } catch (Exception e) {
            LOG.error("Exception saving static output", e);
            return;
        }

        // write the stop tree
        try {
            OutputStream os = StaticDataStore.getOutputStream(request, "stop_trees.dat", "application/octet-stream");
            writeStopTrees(os);
            os.close();
        } catch (Exception e) {
            LOG.error("Exception writing stop trees", e);
            return;
        }

    }

    /** Write metadata for this query */
    public void writeMetadata (OutputStream out) throws IOException {
        Metadata metadata = new Metadata();
        WebMercatorGridPointSet ps = network.getGridPointSet();
        metadata.zoom = ps.zoom;
        metadata.north = ps.north;
        metadata.west = ps.west;
        metadata.width = ps.width;
        metadata.height = ps.height;
        metadata.transportNetwork = request.transportNetworkId;
        metadata.request = request.request;

        JsonUtilities.objectMapper.writeValue(out, metadata);
    }

    /** Write stop trees for this query */
    public void writeStopTrees (OutputStream out) throws IOException {
        // build the stop trees
        LinkedPointSet lps = network.getLinkedGridPointSet();
        lps.makeStopTrees();

        // invert the stop trees
        TIntList[] stopTrees = new TIntList[lps.pointSet.featureCount()];

        // first increment will land at 0
        int stop = -1;
        for (int[] tree : lps.stopTrees) {
            // make sure stop always gets incremented
            stop++;
            if (tree == null)
                continue;

            for (int i = 0; i < tree.length; i+= 2) {
                // tree[i] is the target
                if (stopTrees[tree[i]] == null) {
                    stopTrees[tree[i]] = new TIntArrayList();
                }

                // tree[i + 1] is distance
                stopTrees[tree[i]].add(new int[] { stop, tree[i + 1] });
            }
        }

        // write the trees
        LittleEndianDataOutputStream dos = new LittleEndianDataOutputStream(out);

        int prevStopId = 0;
        int prevTime = 0;

        for (TIntList tree : stopTrees) {
            if (tree == null) {
                dos.writeInt(0);
            }
            else {
                // divide by two because array is jagged, get number of stops
                dos.writeInt(tree.size() / 2);
                for (int i = 0; i < tree.size(); i += 2) {
                    int stopId = tree.get(i);
                    dos.writeInt(stopId - prevStopId);
                    prevStopId = stopId;

                    int time = tree.get(i + 1) / 60;
                    dos.writeInt(time - prevTime);
                    prevTime = time;
                }
            }
        }

        dos.flush();
    }

    public static class Metadata implements Serializable {
        private static final long serialVersionUID = 1L;

        /** Zoom level */
        public int zoom;

        /** westermost pixel */
        public long west;

        /** northernmost pixel */
        public long north;

        /** width, pixels */
        public long width;

        /** height, pixels */
        public long height;

        public String transportNetwork;

        public ProfileRequest request;
    }
}
