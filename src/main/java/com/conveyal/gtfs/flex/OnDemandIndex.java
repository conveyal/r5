package com.conveyal.gtfs.flex;

import com.conveyal.r5.streets.IntHashGrid;
import com.conveyal.r5.streets.VertexStore;
import com.conveyal.r5.transit.TransportNetwork;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Polygon;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.List;

import static com.conveyal.r5.common.GeometryUtils.envelopeToFixed;

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

    /// Returns the number of on-demand services in this index (one per GTFS Flex trip).
    /// Primarily used for assertions in tests.
    public int size () {
        return services.size();
    }

    /// Read-only view of all on-demand services in this index, primarily for tests and reporting.
    public List<OnDemand> allServices () {
        return Collections.unmodifiableList(services);
    }

    /// Potentially merge with findCarRoads, creating and storing a fromEnvelope for polygon + stops.
    /// Then to buildSpatialIndexAsNeeded or rebuildTransientIndex.
    /// As indicated in the IntHashGrid documentation, envelopes are inserted as fixed-precision.
    /// This lazy-init method is synchronized in case it is called in parallel on workers.
    /// In practice we aim to call it once when loading the network and block usage until it's done.
    /// In scenario copies, the reference to a built index will be cloned so rebuild will be skipped.
    public synchronized void indexIfNeeded (TransportNetwork network) {
        if (spatialIndex != null) return;
        // double centerLat = network.getEnvelope().centre().y;
        // spatialIndex = new IntHashGrid(400, centerLat);
        spatialIndex = new IntHashGrid(0.004); // About 444m in latitude direction.
        for (int i = 0; i < services.size(); i++) {
            OnDemand od = services.get(i);
            Polygon fromPolygon = od.fromPolygon;
            if (fromPolygon != null) {
                Envelope env = envelopeToFixed(fromPolygon.getEnvelopeInternal());
                spatialIndex.insert(env, i);
            }
            if (od.fromStopIndexes != null) {
                // TODO pre-convert stops to vertices instead of doing it on demand
                // Then ideally remove the TransportNetwork parameter.
                // Instead of a unified envelope, we could insert separately in the cell of each stop,
                // but that requires cell-level value deduplication.
                Envelope env = new Envelope();
                VertexStore.Vertex vertex = network.streetLayer.vertexStore.getCursor();
                for (int s : od.fromStopIndexes) {
                    int v = network.transitLayer.streetVertexForStop.get(s);
                    if (v < 0) continue; // stop was not linked to street network
                    vertex.seek(v);
                    env.expandToInclude(vertex.getFixedLon(), vertex.getFixedLat());
                }
                spatialIndex.insert(env, i);
            }
        }
    }

    /// Returns a List containing all on-demand services usable by a rider departing the origin at
    /// beginTime whose whole trip must end by endTime, inexpensively overselecting (see
    /// canPickUpDuring). May return an empty list, but never null. In caller, null means no service
    /// is defined at all. Envelope should be in fixed-point WGS84.
    /// Selection on serviceCodes is exact, but both spatial and temporal overselection will occur
    /// (inherited from the spatial index and inexact temporal bounds) so perform further filtering.
    public List<OnDemand> find (Envelope envelope, int beginTime, int endTime, BitSet serviceCodes) {
        final List<OnDemand> available = new ArrayList<>();
        spatialIndex.query(envelope).forEach(s -> {
            OnDemand od = services.get(s);
            if (od.canPickUpDuring(beginTime, endTime, serviceCodes)) available.add(od);
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
