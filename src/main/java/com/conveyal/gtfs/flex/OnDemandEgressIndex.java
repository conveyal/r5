package com.conveyal.gtfs.flex;

import com.conveyal.gtfs.geom.PointInPolygonTester;
import com.conveyal.r5.streets.VertexStore;
import com.conveyal.r5.transit.TransportNetwork;
import gnu.trove.map.TIntObjectMap;
import gnu.trove.map.hash.TIntObjectHashMap;
import gnu.trove.set.TIntSet;
import gnu.trove.set.hash.TIntHashSet;

import java.util.ArrayList;
import java.util.List;

/// Records which on-demand services can pick up a rider alighting from scheduled transit at each
/// transit stop. Services are interpreted as directional on both the access and egress legs.
/// The mapping depends only on the network's stops and services, not on the request, so one
/// instance is built per TransportNetwork and reused across requests (like MeetingAreas).
/// Availability on a particular date and time is evaluated per request during propagation.
public class OnDemandEgressIndex {

    private final TIntObjectMap<List<OnDemand>> servicesForStop = new TIntObjectHashMap<>();

    public OnDemandEgressIndex (TransportNetwork network) {
        OnDemandIndex onDemandIndex = network.transitLayer.onDemandIndex;
        if (onDemandIndex == null) return;
        int nStops = network.transitLayer.getStopCount();
        VertexStore.Vertex vertex = network.streetLayer.vertexStore.getCursor();
        for (OnDemand od : onDemandIndex.allServices()) {
            if (od.fromStopIndexes != null) {
                for (int stop : od.fromStopIndexes) {
                    addService(stop, od);
                }
            }
            if (od.fromPolygon != null) {
                PointInPolygonTester tester = new PointInPolygonTester(od.fromPolygon);
                for (int stop = 0; stop < nStops; stop++) {
                    int v = network.transitLayer.streetVertexForStop.get(stop);
                    if (v < 0) continue; // The stop is not linked to the street network.
                    vertex.seek(v);
                    if (tester.contains(vertex.getLon(), vertex.getLat())) {
                        addService(stop, od);
                    }
                }
            }
        }
    }

    private void addService (int stop, OnDemand od) {
        List<OnDemand> services = servicesForStop.get(stop);
        if (services == null) {
            services = new ArrayList<>();
            servicesForStop.put(stop, services);
        }
        services.add(od);
    }

    /// Returns the services whose pick-up place contains the given stop, or null when there are none.
    public List<OnDemand> servicesForStop (int stop) {
        return servicesForStop.get(stop);
    }

    /// Returns the set of all stops where at least one service can pick up an alighting rider.
    public TIntSet stops () {
        return new TIntHashSet(servicesForStop.keySet());
    }

    /// Returns true when no service can pick up an alighting rider at any stop.
    public boolean isEmpty () {
        return servicesForStop.isEmpty();
    }

}
