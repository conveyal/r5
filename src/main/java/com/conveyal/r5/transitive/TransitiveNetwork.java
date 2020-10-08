package com.conveyal.r5.transitive;

import com.conveyal.r5.streets.VertexStore;
import com.conveyal.r5.transit.RouteInfo;
import com.conveyal.r5.transit.TransitLayer;
import com.conveyal.r5.transit.TripPattern;
import org.locationtech.jts.geom.LineString;

import java.util.ArrayList;
import java.util.List;

/**
 * A representation of a TransitLayer as a Transitive.js network.
 * See https://github.com/conveyal/transitive.js/wiki/Transitive-Conceptual-Overview
 * This class is intended to be serialized out as JSON for communication with web UIs that use Transitive.js.
 * @author mattwigway
 */
public class TransitiveNetwork {

    public final List<TransitiveRoute> routes;
    public final List<TransitiveStop> stops;
    public final List<TransitivePattern> patterns;
    // Transitive 'places' and 'journeys' are not currently included. These are added by the Javascript client.

    public TransitiveNetwork (TransitLayer transitLayer) {
        routes = convertRoutes(transitLayer);
        stops = convertStops(transitLayer);
        patterns = convertPatterns(transitLayer, routes);
    }

    /** Convert R5 routes to Transitive routes. */
    private static List<TransitiveRoute> convertRoutes(TransitLayer transitLayer) {
        List<TransitiveRoute> routes = new ArrayList<>();
        int routeIndex = 0;
        for (RouteInfo r5route : transitLayer.routes) {
            TransitiveRoute transitiveRoute = new TransitiveRoute();
            transitiveRoute.agency_id = r5route.agency_id;
            transitiveRoute.route_short_name = r5route.route_short_name;
            transitiveRoute.route_long_name = r5route.route_long_name;
            transitiveRoute.route_id = Integer.toString(routeIndex++);
            transitiveRoute.route_type = r5route.route_type;
            transitiveRoute.route_color = r5route.color;
            // Transitive always expects route short name to be defined, and the GTFS spec requires use of the empty
            // string when the field is empty. GTFS lib converts that to null, convert it back.
            if (transitiveRoute.route_long_name == null) transitiveRoute.route_long_name = "Route";
            if (transitiveRoute.route_short_name == null) transitiveRoute.route_short_name =
                    transitiveRoute.route_long_name.split("[^A-Za-z0-9]")[0];
            routes.add(transitiveRoute);
        }
        return routes;
    }

    /**
     * Convert R5 patterns to Transitive patterns.
     * @param routes just to get the normalized Transitive route names. Pull this out into a static method.
     */
    private static List<TransitivePattern> convertPatterns (TransitLayer transitLayer, List<TransitiveRoute> routes) {
        List<TransitivePattern> patterns = new ArrayList<>();
        for (int patternIdx = 0; patternIdx < transitLayer.tripPatterns.size(); patternIdx++) {
            TripPattern r5pattern = transitLayer.tripPatterns.get(patternIdx);
            TransitivePattern transitivePattern = new TransitivePattern();
            transitivePattern.pattern_id = patternIdx + "";
            transitivePattern.pattern_name = routes.get(r5pattern.routeIndex).route_short_name;
            transitivePattern.route_id = r5pattern.routeIndex + "";
            transitivePattern.stops = getStopRefs(r5pattern, transitLayer);
            patterns.add(transitivePattern);
        }
        return patterns;
    }

    /** Convert R5 stops to Transitive stops. */
    private static List<TransitiveStop> convertStops(TransitLayer transitLayer) {
        List<TransitiveStop> stops = new ArrayList<>();
        VertexStore.Vertex v = transitLayer.parentNetwork.streetLayer.vertexStore.getCursor();
        for (int sidx = 0; sidx < transitLayer.getStopCount(); sidx++) {
            int vidx = transitLayer.streetVertexForStop.get(sidx);
            // Transitive requires coordinates for every stop,
            // but currently R5 is not saving coordinates for unlinked stops.
            // see https://github.com/conveyal/r5/issues/33
            // As a stopgap, for unlinked stops use the location of the 0th stop.
            v.seek(vidx < 0 ? 0 : vidx);
            TransitiveStop ts = new TransitiveStop();
            ts.stop_lat = v.getLat();
            ts.stop_lon = v.getLon();
            ts.stop_id = sidx + "";
            ts.stop_name = transitLayer.stopNames.get(sidx);
            stops.add(ts);
        }
        return stops;
    }

    /**
     * @return a list of Transitive stop references for all the stops in the supplied r5 pattern, including geometries
     * for the path the vehicle takes after each stop (except the last one, which will be null).
     */
    public static List<TransitivePattern.StopIdRef> getStopRefs (TripPattern r5pattern, TransitLayer transitLayer) {
        List<LineString> geometries = r5pattern.getHopGeometries(transitLayer);
        List<TransitivePattern.StopIdRef> stopRefs = new ArrayList<>();
        for (int stopPos = 0; stopPos < r5pattern.stops.length; stopPos++) {
            String transitiveStopId = Integer.toString(r5pattern.stops[stopPos]);
            LineString hopGeometry = (stopPos < geometries.size()) ? geometries.get(stopPos) : null;
            stopRefs.add(new TransitivePattern.StopIdRef(transitiveStopId, hopGeometry));
        }
        return stopRefs;
    }

}
