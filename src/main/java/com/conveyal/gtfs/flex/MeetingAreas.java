package com.conveyal.gtfs.flex;

import com.conveyal.r5.profile.StreetMode;
import com.conveyal.r5.streets.EdgeStore;
import com.conveyal.r5.streets.Split;
import com.conveyal.r5.streets.StreetLayer;
import com.conveyal.r5.streets.StreetRouter;
import com.conveyal.r5.streets.VertexStore;
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
/// The street search that discovers a meeting area minimizes distance, making it independent of
/// walk speed. On the egress leg there is no per-request walk search, so the distance of the walk
/// from stop to meeting vertex is converted to a duration at the request-specified walk speed.
///
/// The discovery search is supplemented by one straight-line lookup of the nearest drivable edge to
/// the stop. When that edge is about as close as the stop's walk-linked edge, it is also used.
/// This corrects for imprecise stop placement which might make it ambiguous which road to use.
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
    /// services are allowed anywhere in the area. This budget applies to stops whose geographic
    /// position is far away from a curb on the drivable network, like a train station.
    public static final int MEETING_AREA_RADIUS_METERS = 500;

    /// This much smaller discovery budget is used for stops whose walk link is short and lands
    /// immediately on a drivable street. Such stops are positioned expressly for on-demand service,
    /// and "at stop S" is interpreted narrowly. The area is still discovered by a search rather
    /// than taken to be the single linked vertex, because that link can attach to the wrong car
    /// street or have poor reachability among one-way streets.
    public static final int CURB_STOP_RADIUS_METERS = 100;

    /// Straight-line distance tolerance for also attaching to the drivable edge nearest a stop.
    /// A stop beside a true barrier may wrongly acquire meeting points across the barrier.
    public static final int NEARBY_DRIVABLE_EDGE_TOLERANCE_METERS = 5;

    private final TransportNetwork network;

    /// For each relevant stop index, a map defining that stop's meeting area. These values map
    /// reached drivable-edge vertices to their walk network distance from the stop in millimeters.
    /// Values are never null. A stop with no drivable street in reach has an empty map.
    private final Map<Integer, TIntIntMap> areaForStop = new ConcurrentHashMap<>();

    public MeetingAreas (TransportNetwork network) {
        this.network = network;
    }

    /// Return the meeting area map for the given stop index.
    /// This map is computed and cached on first use.
    public TIntIntMap areaWithDistances (int stop) {
        return areaForStop.computeIfAbsent(stop, this::discover);
    }

    /// Returns the union of the given stops' meeting areas as one set of vertices. These are the
    /// vertices where a rider can board/alight from one location_group endpoint of an on-demand service.
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
        int radiusMeters = discoveryRadiusMeters(stopVertex);
        StreetRouter router = new StreetRouter(network.streetLayer);
        router.streetMode = StreetMode.WALK;
        router.quantityToMinimize = StreetRouter.State.RoutingVariable.DISTANCE_MILLIMETERS;
        router.distanceLimitMeters = radiusMeters;
        router.setOrigin(stopVertex);
        router.route();
        router.getReachedVertices().forEachEntry((vertex, distanceMm) -> {
            if (touchesCarStreet(vertex)) {
                area.put(vertex, distanceMm);
            }
            return true;
        });
        addNearbyDrivableEdge(stopVertex, radiusMeters, area);
        if (area.isEmpty()) {
            LOG.warn("No drivable street within {} meters walking distance of stop {}. " +
                "On-demand service cannot serve it.",
                radiusMeters, network.transitLayer.stopIdForIndex.get(stop));
        }
        return area;
    }

    private void addNearbyDrivableEdge (int stopVertex, int radiusMeters, TIntIntMap area) {
        VertexStore.Vertex vertex = network.streetLayer.vertexStore.getCursor(stopVertex);
        Split split = network.streetLayer.findSplit(vertex.getLat(), vertex.getLon(),
            StreetLayer.LINK_RADIUS_METERS, StreetMode.CAR);
        if (split == null) {
            return; // No drivable edge anywhere near the stop.
        }
        long thresholdMm = (long) walkLinkLengthMm(stopVertex)
            + NEARBY_DRIVABLE_EDGE_TOLERANCE_METERS * 1000;
        if (split.distanceToEdge_mm > thresholdMm) {
            return; // The drivable edge is clearly farther away than the walk link's street.
        }
        long budgetMm = (long) radiusMeters * 1000;
        addIfCloser(area, split.vertex0, split.distanceToEdge_mm + split.distance0_mm, budgetMm);
        addIfCloser(area, split.vertex1, split.distanceToEdge_mm + split.distance1_mm, budgetMm);
    }

    /// The distance from the given stop to the street it is linked to. That is, the length of its
    /// walk link edge. Current code creates exactly one link edge per stop. The shortest is used so
    /// this stays correct if stop vertices ever acquire additional links.
    private int walkLinkLengthMm (int stopVertex) {
        EdgeStore.Edge edge = network.streetLayer.edgeStore.getCursor();
        int min = Integer.MAX_VALUE;
        for (TIntIterator it = network.streetLayer.outgoingEdges.get(stopVertex).iterator(); it.hasNext(); ) {
            edge.seek(it.next());
            min = Math.min(min, edge.getLengthMm());
        }
        return min;
    }

    private static void addIfCloser (TIntIntMap area, int vertex, int distanceMm, long budgetMm) {
        if (distanceMm > budgetMm) {
            return;
        }
        if (!area.containsKey(vertex) || area.get(vertex) > distanceMm) {
            area.put(vertex, distanceMm);
        }
    }

    /// Returns the walk budget for discovering the given stop's meeting area. Stops whose walk
    /// link is short and lands directly on a drivable street get the smaller budget. The link
    /// length condition matters in cases where the link is longer than the smaller budget.
    private int discoveryRadiusMeters (int stopVertex) {
        EdgeStore.Edge edge = network.streetLayer.edgeStore.getCursor();
        for (TIntIterator it = network.streetLayer.outgoingEdges.get(stopVertex).iterator(); it.hasNext(); ) {
            edge.seek(it.next());
            if (edge.getLengthMm() <= CURB_STOP_RADIUS_METERS * 1000
                && touchesCarStreet(edge.getToVertex())) {
                return CURB_STOP_RADIUS_METERS;
            }
        }
        return MEETING_AREA_RADIUS_METERS;
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
