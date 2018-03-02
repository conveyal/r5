package com.conveyal.r5.multipoint;

import com.conveyal.r5.analyst.PersistenceBuffer;
import com.conveyal.r5.analyst.WebMercatorGridPointSet;
import com.conveyal.r5.analyst.cluster.AnalysisTask;
import com.conveyal.r5.analyst.cluster.AnalystWorker;
import com.conveyal.r5.analyst.error.TaskError;
import com.conveyal.r5.common.JsonUtilities;
import com.conveyal.r5.profile.ProfileRequest;
import com.conveyal.r5.transit.TransportNetwork;
import com.conveyal.r5.transitive.TransitiveNetwork;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.util.List;

/**
 * Create metadata that provides the context for a whole bucket/directory full of static site output.
 * TODO refactor to merge with the static inner class Metadata, using public/private fields.
 */
public class MultipointMetadata {
    private static final Logger LOG = LoggerFactory.getLogger(MultipointMetadata.class);

    private AnalysisTask request;
    private TransportNetwork network;

    public MultipointMetadata (AnalysisTask req, TransportNetwork network) {
        this.request = req;
        this.network = network;
    }

    // generate and write out metadata
    public void write () {
        try {
            Metadata metadata = new Metadata();
            WebMercatorGridPointSet ps = network.gridPointSet;
            metadata.zoom = ps.zoom;
            metadata.west = ps.west;
            metadata.north = ps.north;
            metadata.width = ps.width;
            metadata.height = ps.height;
            metadata.transportNetwork = request.graphId;
            metadata.transitiveData = new TransitiveNetwork(network.transitLayer);
            metadata.request = request;
            metadata.scenarioApplicationWarnings = network.scenarioApplicationWarnings;

            PersistenceBuffer persistenceBuffer = new PersistenceBuffer();
            persistenceBuffer.setMimeType("application/json");
            // Retrieving the PersistenceBuffer's InputStream will automatically close this OutputStream.
            JsonUtilities.objectMapper.writeValue(persistenceBuffer.getOutputStream(), metadata);
            persistenceBuffer.doneWriting();
            AnalystWorker.filePersistence.saveStaticSiteData(request, "query.json", persistenceBuffer);
        } catch (Exception e) {
            LOG.error("Exception saving static metadata", e);
            return;
        }
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