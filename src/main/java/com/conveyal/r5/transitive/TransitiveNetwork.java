package com.conveyal.r5.transitive;

import com.conveyal.r5.common.GeometryUtils;
import com.conveyal.r5.streets.StreetLayer;
import com.conveyal.r5.streets.VertexStore;
import com.conveyal.r5.transit.RouteInfo;
import com.conveyal.r5.transit.TransitLayer;
import com.conveyal.r5.transit.TripPattern;
import com.vividsolutions.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.LineString;
import com.vividsolutions.jts.linearref.LinearLocation;
import com.vividsolutions.jts.linearref.LocationIndexedLine;
import gnu.trove.map.TIntObjectMap;
import gnu.trove.map.hash.TIntObjectHashMap;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

/**
 * A representation of a TransitLayer as a Transitive.js network.
 * See https://github.com/conveyal/transitive.js/wiki/Transitive-Conceptual-Overview
 * This class is intended to be serialized out as JSON for communication with web UIs that use Transitive.js.
 * @author mattwigway
 */
public class TransitiveNetwork {

    public List<TransitiveRoute> routes = new ArrayList<>();
    public List<TransitiveStop> stops = new ArrayList<>();
    public List<TransitivePattern> patterns = new ArrayList<>();
    // Transitive 'places' and 'journeys' are not currently included. These are added by the Javascript client.

    public TransitiveNetwork (TransitLayer layer, StreetLayer streetLayer) {

        // Scan over all R5 trip patterns, converting them to Transitive patterns
        // and creating Transitive Routes as they are encountered via the patterns.
        TIntObjectMap<TransitiveRoute> transitiveRoutes = new TIntObjectHashMap<>();
        for (int patternIdx = 0; patternIdx < layer.tripPatterns.size(); patternIdx++) {
            TripPattern r5pattern = layer.tripPatterns.get(patternIdx);

            // If this route has not been seen yet, create a Transitive route
            if (!transitiveRoutes.containsKey(r5pattern.routeIndex)) {
                // TODO save enough information to get rid of all of this boilerplate
                // TODO ^ save it where?
                TransitiveRoute route = new TransitiveRoute();
                RouteInfo ri = layer.routes.get(r5pattern.routeIndex);
                route.agency_id = ri.agency_id;
                route.route_short_name = ri.route_short_name;
                route.route_long_name = ri.route_long_name;
                route.route_id = r5pattern.routeIndex + "";
                route.route_type = ri.route_type;
                route.route_color = ri.color;

                // Transitive always expects route short name to be defined, and the GTFS spec requires use of the empty
                // string when the field is empty. GTFS lib converts that to null, convert it back.
                if (route.route_long_name == null) route.route_long_name = "Route";
                if (route.route_short_name == null) route.route_short_name = route.route_long_name.split("[^A-Za-z0-9]")[0];

                transitiveRoutes.put(r5pattern.routeIndex, route);
            }

            // Create a new transitive Pattern corresponding to the current R5 pattern.
            TransitivePattern transitivePattern = new TransitivePattern();
            // TODO boilerplate
            // TODO ^ why does this say boilerplate?
            transitivePattern.pattern_id = patternIdx + "";
            transitivePattern.pattern_name = transitiveRoutes.get(r5pattern.routeIndex).route_short_name;
            transitivePattern.route_id = r5pattern.routeIndex + "";
            transitivePattern.stops = getStopRefs(r5pattern, streetLayer);

            patterns.add(transitivePattern);
        }

        this.routes.addAll(transitiveRoutes.valueCollection());

        VertexStore.Vertex v = layer.parentNetwork.streetLayer.vertexStore.getCursor();

        // Convert R5 stops to Transitive stops.
        for (int sidx = 0; sidx < layer.getStopCount(); sidx++) {
            TransitiveStop ts = new TransitiveStop();
            int vidx = layer.streetVertexForStop.get(sidx);

            ts.stop_id = sidx + "";

            if (vidx != -1) {
                v.seek(vidx);
                ts.stop_lat = v.getLat();
                ts.stop_lon = v.getLon();
            } else {
                // TODO this should actually know where unlinked stop are
                // see issue 33
                // at least put the stop in the map somewhere
                v.seek(0);
                ts.stop_lat = v.getLat();
                ts.stop_lon = v.getLon();
            }
            ts.stop_name = layer.stopNames.get(sidx);
            stops.add(ts);
        }
    }

    /**
     * @param streetLayer for looking up stop coordinates when the R5 pattern does not have a geometry/shape.
     * @return a list of Transitive stop references for all the stops in the supplied r5 pattern, including geometries
     * for the path the vehicle takes after each stop.
     */
    public List<TransitivePattern.StopIdRef> getStopRefs (TripPattern r5pattern, StreetLayer streetLayer) {

        List<TransitivePattern.StopIdRef> stopRefs = new ArrayList<>();

        if (r5pattern.shape != null) {
            // This pattern has a shape. Split that shape up into segments between each pair of stops.
            LocationIndexedLine unprojectedLine = new LocationIndexedLine(r5pattern.shape);
            for (int stopPos = 0; stopPos < r5pattern.stops.length; stopPos++) {
                LineString geometry = null;
                // Using shape segments and fractions stored when creating
                if (stopPos < r5pattern.stops.length - 1) {
                    LinearLocation from =
                        new LinearLocation(r5pattern.stopShapeSegment[stopPos], r5pattern.stopShapeFraction[stopPos]);
                    LinearLocation to =
                        new LinearLocation(r5pattern.stopShapeSegment[stopPos + 1], r5pattern.stopShapeFraction[stopPos + 1]);
                    geometry = (LineString) unprojectedLine.extractLine(from, to);
                }
                stopRefs.add(new TransitivePattern.StopIdRef(Integer.toString(r5pattern.stops[stopPos]), geometry));
            }
        } else {
            // This pattern does not have a shape, but Transitive expects geometries. Use straight lines between stops.
            VertexStore.Vertex v = streetLayer.vertexStore.getCursor();
            Coordinate[] coords = IntStream.of(r5pattern.stops).mapToObj(sidx -> {
                        v.seek(sidx);
                        return new Coordinate(v.getLon(), v.getLat());
                    }).toArray(Coordinate[]::new);

            // fill in unlinked stops with nearest coordinate
            Coordinate last = null;
            for (int i = 0; i < coords.length; i++) {
                if (coords[i] == null) coords[i] = last;
                else last = coords[i];
            }
            last = null;
            for (int i = coords.length - 1; i >= 0; i--) {
                if (coords[i] == null) coords[i] = last;
                else last = coords[i];
            }
            for (int stopPos = 0; stopPos < r5pattern.stops.length; stopPos++) {
                LineString geometry = null;
                if (stopPos < r5pattern.stops.length - 1) {
                    geometry = GeometryUtils.geometryFactory.createLineString(new Coordinate[] { coords[stopPos], coords[stopPos + 1] });
                }
                stopRefs.add(new TransitivePattern.StopIdRef(Integer.toString(r5pattern.stops[stopPos]), geometry));
            }
        }
        return stopRefs;
    }

}
