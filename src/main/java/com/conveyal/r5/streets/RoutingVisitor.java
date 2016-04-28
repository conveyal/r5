package com.conveyal.r5.streets;

import com.conveyal.r5.common.GeoJsonFeature;
import com.conveyal.r5.profile.StreetMode;

import java.util.ArrayList;
import java.util.List;

/**
 * Visitor for debugging purpose
 *
 * It outputs full state graph in plan debugger
 * Created by mabu on 10.12.2015.
 */
public class RoutingVisitor {
    private final StreetMode streetMode;

    private List<GeoJsonFeature> features;
    private final EdgeStore edgeStore;

    /**
     * Mode should be in the state itself
     *
     * @param edgeStore streetLayer edgeStore
     * @param streetMode of this routing
     */
    public RoutingVisitor(EdgeStore edgeStore, StreetMode streetMode) {
        this.features = new ArrayList<>();
        this.edgeStore = edgeStore;
        this.streetMode = streetMode;
    }

    /**
     * Saves current state geometry mode and weight as geoJSON feature properties
     *
     * in list of features. It is used in full state graph when debugging
     * @param state
     */
    public void visitVertex(StreetRouter.State state) {
        Integer edgeIdx = state.backEdge;
        if (!(edgeIdx == null || edgeIdx == -1)) {
            EdgeStore.Edge edge = edgeStore.getCursor(edgeIdx);
            GeoJsonFeature feature = new GeoJsonFeature(edge.getGeometry());
            feature.addProperty("weight", state.weight);
            //FIXME: this is temporary until mode isn't in state itself
            feature.addProperty("mode", streetMode);
            features.add(feature);
        }
    }

    public List<GeoJsonFeature> getFeatures() {
        return features;
    }
}
