package com.conveyal.r5.streets;

import com.conveyal.r5.common.GeoJsonFeature;

import java.util.ArrayList;
import java.util.List;

/**
 * Callbacks for debugging street routing.
 * It accumulates all the steps in routing as GeoJSON.
 * Created by mabu on 10.12.2015.
 */
public class DebugRoutingVisitor implements RoutingVisitor {

    private List<GeoJsonFeature> features;
    private final EdgeStore edgeStore;

    /**
     * Mode should be in the state itself
     *  @param edgeStore streetLayer edgeStore
     *
     */
    public DebugRoutingVisitor(EdgeStore edgeStore) {
        this.features = new ArrayList<>();
        this.edgeStore = edgeStore;
    }

    /**
     * Saves current state geometry mode and weight as geoJSON feature properties
     *
     * in list of features. It is used in full state graph when debugging
     * @param state
     */
    @Override
    public void visitVertex(StreetRouter.State state) {
        Integer edgeIdx = state.backEdge;
        if (!(edgeIdx == null || edgeIdx == -1)) {
            EdgeStore.Edge edge = edgeStore.getCursor(edgeIdx);
            GeoJsonFeature feature = new GeoJsonFeature(edge.getGeometry());
            feature.addProperty("mode", state.streetMode);
            feature.addProperty("backEdge", state.backEdge);
            features.add(feature);
        }
    }

    public List<GeoJsonFeature> getFeatures() {
        return features;
    }
}
