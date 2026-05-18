package com.conveyal.gtfs.flex;

import com.conveyal.r5.streets.IntHashGrid;
import com.conveyal.r5.streets.VertexStore;
import com.conveyal.r5.transit.TransportNetwork;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Polygon;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

import static com.conveyal.r5.common.GeometryUtils.floatingWgsEnvelopeToFixed;

/// Groups together all OnDemand instances within the TransportNetwork and provides some related
/// methods for indexing and searching them. The transient spatial index handles services is
/// important for reasonable performance with services that have large numbers of polygons.
/// See IndexedPolygonCollection and PickupWaitTimes, with which this should eventually be merged.
public class OnDemandIndex implements Serializable {
    private List<OnDemand> services = new ArrayList<>();
    private transient IntHashGrid spatialIndex = null;

    public void add (OnDemand onDemand) {
        services.add(onDemand);
    }

    /// Potentially merge with findCarRoads, creating and storing a fromEnvelope for polygon + stops.
    /// Then to buildSpatialIndexAsNeeded or rebuildTransientIndex.
    /// As indicated in the IntHashGrid documentation, envelopes are inserted as fixed-precision.
    public void indexIfNeeded (TransportNetwork network) {
        if (spatialIndex != null) return;
        spatialIndex = new IntHashGrid();
        for (int i = 0; i < services.size(); i++) {
            OnDemand od = services.get(i);
            Polygon fromPolygon = od.fromPolygon;
            if (fromPolygon != null) {
                Envelope env = floatingWgsEnvelopeToFixed(fromPolygon.getEnvelopeInternal());
                spatialIndex.insert(env, i);
            }
            if (od.fromStopIndexes != null) {
                // TODO pre-convert stops to vertices instead of doing it on demand
                // Then ideally remove the TransportNetwork parameter.
                Envelope env = new Envelope();
                VertexStore.Vertex vertex = network.streetLayer.vertexStore.getCursor();
                for (int s : od.fromStopIndexes) {
                    int v = network.transitLayer.streetVertexForStop.get(s);
                    vertex.seek(v);
                    env.expandToInclude(vertex.getFixedLon(), vertex.getFixedLat());
                }
                spatialIndex.insert(env, i);
            }
        }
    }

    /// Returns a List containing all on-demand services that can be used given the parameters.
    /// May return an empty list, but not null. Elsewhere, null means no service is defined at all.
    /// Envelope should be in fixed-point WGS84. May overselect from the spatial index, filter.
    /// We need to find multiple services available at the same location and try to use them all.
    public List<OnDemand> find (Envelope envelope, int time, BitSet serviceCodes) {
        final List<OnDemand> available = new ArrayList<>();
        spatialIndex.query(envelope).forEach(s -> {
            OnDemand od = services.get(s);
            if (od.canPickUpAt(time, serviceCodes)) available.add(od);
            return true;
        });
        return available;
    }

    /// Call after the street and transit layers are linked and derived indexes are built.
    /// Works around the fact that transit stops may not be directly connected to drivable roads.
    public void findCarRoads (TransportNetwork network) {
        for (OnDemand onDemand : services) {
            onDemand.findCarEdges(network);
        }
    }

}
