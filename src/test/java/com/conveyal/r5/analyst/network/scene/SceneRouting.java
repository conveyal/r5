package com.conveyal.r5.analyst.network.scene;

import com.conveyal.gtfs.flex.OnDemand;
import com.conveyal.r5.common.GeometryUtils;
import com.conveyal.r5.profile.ProfileRequest;
import com.conveyal.r5.profile.StreetMode;
import com.conveyal.r5.streets.StreetRouter;
import com.conveyal.r5.streets.VertexStore;
import com.conveyal.r5.transit.TransportNetwork;

import java.util.List;

/// Static helper methods for routing on networks built from Scenes, shared by the scene test
/// classes. Origins and destinations are given in scene meter coordinates and converted to WGS84
/// internally. The on-demand method reproduces the access leg sequence of TravelTimeComputer,
/// so tests exercise the same street routing calls as production without the surrounding
/// travel time surface and propagation machinery.
class SceneRouting {

    /// A departure time in the middle of the day, within the service windows used by test scenes.
    static final int NOON = 12 * 3600;

    /// A generous limit that never cuts off searches on the small scenes used in tests.
    private static final int TIME_LIMIT_SECONDS = 2 * 3600;

    /// The walk speed tests should use when predicting travel times, matching the
    /// ProfileRequest default.
    static final double WALK_SPEED = new ProfileRequest().walkSpeed;

    /// Create a request for the single date on which all scene GTFS services run,
    /// with a one hour departure window centered on noon.
    static ProfileRequest defaultRequest () {
        ProfileRequest request = new ProfileRequest();
        request.date = Scene.SERVICE_DATE;
        request.fromTime = NOON - 1800;
        request.toTime = NOON + 1800;
        return request;
    }

    /// Run a street search in the given mode from the given scene coordinates, minimizing
    /// duration. Throws an exception if the origin cannot be linked to the street network.
    static StreetRouter route (TransportNetwork network, Scene scene, double x, double y, StreetMode mode) {
        StreetRouter router = new StreetRouter(network.streetLayer);
        router.profileRequest = defaultRequest();
        router.streetMode = mode;
        router.timeLimitSeconds = TIME_LIMIT_SECONDS;
        if (!router.setOrigin(scene.latForY(y), scene.lonForXY(x, y))) {
            throw new IllegalArgumentException(
                String.format("Origin (%f, %f) could not be linked to the street network.", x, y));
        }
        router.route();
        return router;
    }

    /// Run a street search in the given mode starting at an existing street vertex, minimizing
    /// duration. Unlike the coordinate variant, this does not link a new origin point, so paths
    /// measured from junction or stop vertices contain only network edges.
    static StreetRouter routeFromVertex (TransportNetwork network, int vertex, StreetMode mode) {
        StreetRouter router = new StreetRouter(network.streetLayer);
        router.profileRequest = defaultRequest();
        router.streetMode = mode;
        router.timeLimitSeconds = TIME_LIMIT_SECONDS;
        router.setOrigin(vertex);
        router.route();
        return router;
    }

    /// Run a walk search from the given scene coordinates, then extend it with one on-demand
    /// ride and a final walk continuation. This is the sequence TravelTimeComputer performs for
    /// the access leg when the ON_DEMAND flag is set: ride results are clipped to the service's
    /// drop-off area, merged into the walk search, and walking resumes from every merged state.
    /// The returned router therefore holds the best times reachable by any combination of
    /// walking and one ride on the given service.
    static StreetRouter routeWithOnDemand (TransportNetwork network, Scene scene, double x, double y, OnDemand od) {
        StreetRouter walk = route(network, scene, x, y, StreetMode.WALK);
        StreetRouter ride = walk.copyAndRouteFor(od, NOON);
        ride.clipStates(od);
        walk.mergeStatesFrom(ride);
        walk.keepRoutingOnFoot();
        return walk;
    }

    /// Find the built on-demand service rendered from the SceneOnDemand with the given id.
    static OnDemand onDemand (TransportNetwork network, String id) {
        return network.transitLayer.onDemandIndex.allServices().stream()
            .filter(od -> id.equals(od.id))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("No on-demand service with id " + id));
    }

    /// Find the index of the stop rendered from the SceneStop with the given id.
    /// Stop ids are feed-scoped in the built network, so we match on the unscoped suffix.
    static int stopIndex (TransportNetwork network, String stopId) {
        List<String> ids = network.transitLayer.stopIdForIndex;
        for (int i = 0; i < ids.size(); i++) {
            String scoped = ids.get(i);
            if (scoped.equals(stopId) || scoped.endsWith(":" + stopId)) {
                return i;
            }
        }
        throw new IllegalArgumentException("No stop with id " + stopId);
    }

    /// Find the street vertex created for the stop rendered from the SceneStop with the given id.
    static int stopVertex (TransportNetwork network, String stopId) {
        return network.transitLayer.streetVertexForStop.get(stopIndex(network, stopId));
    }

    /// Find the index of the street vertex lying at the given scene coordinates, within a small
    /// tolerance covering coordinate conversion error. Throws if no vertex is close enough,
    /// so tests fail clearly when a scene does not produce the vertex they expect.
    static int vertexAt (TransportNetwork network, Scene scene, double x, double y) {
        double lat = scene.latForY(y);
        double lon = scene.lonForXY(x, y);
        VertexStore vertexStore = network.streetLayer.vertexStore;
        VertexStore.Vertex vertex = vertexStore.getCursor();
        int bestIndex = -1;
        double bestDistance = 2.0;
        for (int v = 0; v < vertexStore.getVertexCount(); v++) {
            vertex.seek(v);
            double distance = GeometryUtils.distance(lat, lon, vertex.getLat(), vertex.getLon());
            if (distance < bestDistance) {
                bestIndex = v;
                bestDistance = distance;
            }
        }
        if (bestIndex < 0) {
            throw new IllegalArgumentException(
                String.format("No street vertex within 2 meters of (%f, %f).", x, y));
        }
        return bestIndex;
    }

}
