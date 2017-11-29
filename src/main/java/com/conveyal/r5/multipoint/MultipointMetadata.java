package com.conveyal.r5.multipoint;

import com.conveyal.r5.analyst.LittleEndianIntOutputStream;
import com.conveyal.r5.analyst.WebMercatorGridPointSet;
import com.conveyal.r5.analyst.cluster.AnalysisTask;
import com.conveyal.r5.analyst.error.TaskError;
import com.conveyal.r5.common.JsonUtilities;
import com.conveyal.r5.profile.ProfileRequest;
import com.conveyal.r5.streets.LinkedPointSet;
import com.conveyal.r5.transit.TransportNetwork;
import com.conveyal.r5.transitive.TransitiveNetwork;
import com.google.common.io.CountingOutputStream;
import com.google.common.io.LittleEndianDataOutputStream;
import gnu.trove.list.TIntList;
import gnu.trove.list.array.TIntArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.Serializable;
import java.util.List;

/**
 * Create metadata that can be shared by a batch of requests
 */
public class MultipointMetadata implements Runnable {
    private static final Logger LOG = LoggerFactory.getLogger(MultipointMetadata.class);

    private MultipointRequest request;
    private TransportNetwork network;

    public MultipointMetadata (MultipointRequest req, TransportNetwork network) {
        this.request = req;
        this.network = network;
    }

    @Override
    public void run () {
        // first generate and write out metadata

        try {
            OutputStream os = MultipointDataStore.getOutputStream(request, "query.json", "application/json");
            writeMetadata(os);
            os.close();
        } catch (Exception e) {
            LOG.error("Exception saving static output", e);
            return;
        }

        // write transitive data (optional at this point but retains compatibility with older clients that don't get the
        // transitive data from query.json)
        try {
            OutputStream os = MultipointDataStore.getOutputStream(request, "transitive.json", "application/json");
            writeTransitiveData(os);
            os.close();
        } catch (Exception e) {
            LOG.error("Exception writing transitive data", e);
            return;
        }

    }

    public void writeTransitiveData(OutputStream os) throws IOException {
        TransitiveNetwork net = new TransitiveNetwork(network.transitLayer);
        JsonUtilities.objectMapper.writeValue(os, net);
    }

    /** Write metadata for this query */
    public void writeMetadata (OutputStream out) throws IOException {
        Metadata metadata = new Metadata();
        WebMercatorGridPointSet ps = network.gridPointSet;
        metadata.zoom = ps.zoom;
        metadata.north = ps.north;
        metadata.west = ps.west;
        metadata.width = ps.width;
        metadata.height = ps.height;
        metadata.transportNetwork = request.transportNetworkId;
        metadata.transitiveData = new TransitiveNetwork(network.transitLayer);
        metadata.request = request.request;
        metadata.scenarioApplicationWarnings = network.scenarioApplicationWarnings;

        JsonUtilities.objectMapper.writeValue(out, metadata);
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

        public TransitiveNetwork transitiveData;

        /** Non fatal warnings encountered applying the scenario */
        public List<TaskError> scenarioApplicationWarnings;
    }

    /** A request for the cluster to produce static metadata */
    public static class MetadataRequest extends AnalysisTask {
        public MultipointRequest request;
        public final String type = "static-metadata";
        @Override
        public ProfileRequest extractProfileRequest() {
            return request.request;
        }

        @Override
        public boolean isHighPriority() {
            return request.bucket == null; // null bucket, results will be returned over HTTP
        }
    }
}