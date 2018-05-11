package com.conveyal.r5.multipoint;

import com.conveyal.r5.analyst.WebMercatorGridPointSet;
import com.conveyal.r5.analyst.cluster.AnalysisTask;
import com.conveyal.r5.analyst.error.TaskError;
import com.conveyal.r5.common.JsonUtilities;
import com.conveyal.r5.profile.ProfileRequest;
import com.conveyal.r5.transit.TransportNetwork;
import com.conveyal.r5.transitive.TransitiveNetwork;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.io.Serializable;
import java.util.List;

/**
 * Create metadata that can be shared by a batch of requests
 */
public class MultipointMetadata {
    private static final Logger LOG = LoggerFactory.getLogger(MultipointMetadata.class);

    private AnalysisTask request;
    private TransportNetwork network;

    public MultipointMetadata (AnalysisTask req, TransportNetwork network) {
        this.request = req;
        this.network = network;
    }

    public void write () {
        // generate and write out metadata

        try {
            OutputStream os = MultipointDataStore.getOutputStream(request, "query.json", "application/json");
            writeMetadata(os);
            os.close();
        } catch (Exception e) {
            LOG.error("Exception saving static metadata", e);
            return;
        }
    }

    /** Write metadata for this query */
    private void writeMetadata (OutputStream out) throws IOException {
        Metadata metadata = new Metadata();
        WebMercatorGridPointSet ps = (WebMercatorGridPointSet) network.pointSet;
        metadata.zoom = ps.zoom;
        metadata.west = ps.west;
        metadata.north = ps.north;
        metadata.width = ps.width;
        metadata.height = ps.height;
        metadata.transportNetwork = request.graphId;
        metadata.transitiveData = new TransitiveNetwork(network.transitLayer);
        metadata.request = request;
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
}