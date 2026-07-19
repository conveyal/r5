package com.conveyal.gtfs.flex;

import com.conveyal.r5.profile.StreetMode;
import com.conveyal.r5.streets.EdgeStore;
import com.conveyal.r5.streets.StreetLayer;
import com.conveyal.r5.streets.StreetRouter;
import com.conveyal.r5.transit.TransportNetwork;
import gnu.trove.iterator.TIntIterator;
import gnu.trove.list.TIntList;
import gnu.trove.map.TIntIntMap;
import gnu.trove.map.hash.TIntIntHashMap;
import gnu.trove.set.TIntSet;
import gnu.trove.set.hash.TIntHashSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandles;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/// MeetingAreas are the set of drivable-edge vertices within a certain walking budget of all stops
/// used in on-demand service via GTFS location_groups. An on-demand (flex) stop is where a car-like
/// vehicle meets the rider, but that stop's geographic location may be in a pedestrianized station
/// area or transit platform with no drivable street nearby. The nearest drivable street by straight
/// line distance could be one the pedestrian cannot actually reach (for example, a frontage road
/// beside or beneath a station separated by barriers).
///
/// Although you might expect stops used purely for on-demand services to be located right on roads,
/// GTFS flex allows location_groups to reuse public transit stops as flex dropoff points. That
/// includes things like rail platforms. To identify truly reachable car-boarding points, a walk
/// search is performed outward from the stop vertex, leaving over the stop's walk link.
///
/// The discovery search minimizes distance so the area is independent of any request walk speed.
/// Its costs are discarded or ignored and only set membership survives. The rider walks from their
/// origin directly to wherever the vehicle can meet them, in time determined by their own search.
///
/// A stop with no drivable street within the budget gets an empty area, logged as a data
/// quality warning, and on-demand service is unusable at that stop. Its walk link continues to
/// provide access to scheduled transit.
///
/// Areas are computed lazily and held in a transient field rather than serialized.
/// This makes scenario application simpler for the moment but we may want to change it later.
///
/// NOTE: MeetingAreas are derived from the bizarrely-named GTFS location_groups which CANNOT
/// contain GTFS locations (polygons) but MUST contain GTFS stops (points).
public class MeetingAreas {

    private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    /// How far the discovery search walks outward from each stop in meters. This defines how
    /// liberally "at stop S" is interpreted in source data, since boarding and alighting on-demand
    /// services are allowed anywhere in the area.
    public static final int MEETING_AREA_RADIUS_METERS = 500;

    private final TransportNetwork network;

    /// For each relevant stop index, a map defining that stop's meeting area. These values map
    /// reached drivable-edge vertices to their walk network distance from the stop in millimeters.
    /// Values are never null. A stop with no drivable street in reach has an empty map.
    private final Map<Integer, TIntIntMap> areaForStop = new ConcurrentHashMap<>();

    public MeetingAreas (TransportNetwork network) {
        this.network = network;
    }

    /// Return the meeting area map for the given stop index. This map is computed and cached on
    /// first use. Distances (the map values) should not be used at this point (see class comment).
    public TIntIntMap areaWithDistances (int stop) {
        return areaForStop.computeIfAbsent(stop, this::discover);
    }

    /// Returns the union of the given stops' meeting area vertex sets. This is the set of vertices
    /// where one can board/alight from one location_group endpoint of an on-demand service.
    public TIntSet unionForStops (int[] stops) {
        TIntSet union = new TIntHashSet();
        for (int stop : stops) {
            union.addAll(areaWithDistances(stop).keySet());
        }
        return union;
    }

    /// Walk outward from the given stop's vertex, minimizing distance, and collect every
    /// settled vertex touching a drivable edge. The search is similar to the per-stop search in
    /// TransferFinder. Note that a vertex incident to an edge allowing cars may still be a poor
    /// vehicle approach. The fact that the area contains many vertices mitigates that risk.
    private TIntIntMap discover (int stop) {
        TIntIntMap area = new TIntIntHashMap();
        int stopVertex = network.transitLayer.streetVertexForStop.get(stop);
        if (stopVertex < 0) {
            LOG.warn("Stop {} is not linked to the street network, so it has no meeting area " +
                "and on-demand service cannot serve it.", network.transitLayer.stopIdForIndex.get(stop));
            return area;
        }
        StreetRouter router = new StreetRouter(network.streetLayer);
        router.streetMode = StreetMode.WALK;
        router.quantityToMinimize = StreetRouter.State.RoutingVariable.DISTANCE_MILLIMETERS;
        router.distanceLimitMeters = MEETING_AREA_RADIUS_METERS;
        router.setOrigin(stopVertex);
        router.route();
        router.getReachedVertices().forEachEntry((vertex, distanceMm) -> {
            if (touchesCarStreet(vertex)) {
                area.put(vertex, distanceMm);
            }
            return true;
        });
        if (area.isEmpty()) {
            LOG.warn("No drivable street within {} meters walking distance of stop {}. " +
                "On-demand service cannot serve it.",
                MEETING_AREA_RADIUS_METERS, network.transitLayer.stopIdForIndex.get(stop));
        }
        return area;
    }

    /// Returns true when any non-link street edge at the given vertex permits cars. Link edges
    /// are excluded because they allow all modes. This prevents one stop's meeting area from
    /// spuriously containing another stop's vertex.
    private boolean touchesCarStreet (int vertex) {
        StreetLayer streetLayer = network.streetLayer;
        EdgeStore.Edge edge = streetLayer.edgeStore.getCursor();
        return anyEdgeAllowsCar(streetLayer.outgoingEdges.get(vertex), edge)
            || anyEdgeAllowsCar(streetLayer.incomingEdges.get(vertex), edge);
    }

    private static boolean anyEdgeAllowsCar (TIntList edges, EdgeStore.Edge edge) {
        for (TIntIterator it = edges.iterator(); it.hasNext(); ) {
            edge.seek(it.next());
            if (edge.getFlag(EdgeStore.EdgeFlag.LINK)) continue;
            if (edge.getFlag(EdgeStore.EdgeFlag.ALLOWS_CAR)) return true;
        }
        return false;
    }

}
