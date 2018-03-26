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

import java.awt.image.MultiPixelPackedSampleModel;
import java.io.Serializable;
import java.util.List;

/**
 * Create metadata that provides the context for a whole bucket/directory full of static site output.
 *
 * Some useful information can be found inside the request:
 * Transport Network ID: request.graphId
 * The output extents come from request as well (west, north, width, height).
 */
public class MultipointMetadata implements Serializable {

    private static final Logger LOG = LoggerFactory.getLogger(MultipointMetadata.class);

    private static final long serialVersionUID = 2L;

    /** The request object sent to the workers to generate these static site regional results. */
    public ProfileRequest request;

    /** Non-fatal warnings encountered applying the scenario to the network for this regional analysis. */
    public List<TaskError> scenarioApplicationWarnings;

    /** Private constructor, so it's only possible to make an instance within the persist() method. */
    private MultipointMetadata() {};

    /**
     * Generate and write out static site metadata files.
     * FIXME this is saving the extents of the entire street layer, rather than the extents set in the request.
     * That's the safe option since static site travel time surfaces could be intersected with any opportunity grid.
     * However the street network may be significantly bigger than the area we actually want to analyze.
     */
    public static void persist (AnalysisTask analysisTask, TransportNetwork network) {
        try {

            MultipointMetadata metadata = new MultipointMetadata();
            metadata.request = analysisTask;
            metadata.scenarioApplicationWarnings = network.scenarioApplicationWarnings;

            // Save the regional analysis request, giving the UI some context to display the results.
            PersistenceBuffer requestBuffer = PersistenceBuffer.serializeAsJson(metadata);
            AnalystWorker.filePersistence.saveStaticSiteData(analysisTask, "request.json", false, requestBuffer);

            // Save transit route data that allows rendering paths with the Transitive library in a separate file.
            TransitiveNetwork transitiveNetwork = new TransitiveNetwork(network.transitLayer);
            PersistenceBuffer transitiveBuffer = PersistenceBuffer.serializeAsJson(transitiveNetwork);
            AnalystWorker.filePersistence.saveStaticSiteData(analysisTask, "transitive.json", false, transitiveBuffer);

        } catch (Exception e) {
            LOG.error("Exception saving static metadata", e);
            return;
        }
    }

}