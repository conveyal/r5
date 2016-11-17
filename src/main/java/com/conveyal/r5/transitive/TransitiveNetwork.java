package com.conveyal.r5.transitive;

import com.conveyal.gtfs.model.Route;
import com.conveyal.gtfs.model.Stop;
import com.conveyal.r5.common.GeometryUtils;
import com.conveyal.r5.streets.StreetLayer;
import com.conveyal.r5.streets.VertexStore;
import com.conveyal.r5.transit.RouteInfo;
import com.conveyal.r5.transit.TransitLayer;
import com.conveyal.r5.transit.TripPattern;
import com.conveyal.r5.util.LocationIndexedLineInLocalCoordinateSystem;
import com.vividsolutions.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.Geometry;
import com.vividsolutions.jts.geom.LineString;
import com.vividsolutions.jts.linearref.LinearLocation;
import com.vividsolutions.jts.linearref.LocationIndexedLine;
import gnu.trove.map.TIntObjectMap;
import gnu.trove.map.hash.TIntObjectHashMap;
import org.apache.commons.codec.binary.Hex;
import org.apache.commons.math3.geometry.euclidean.threed.Line;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * A representation of a TransitLayer as a Transitive network.
 * See https://github.com/conveyal/transitive.js/wiki/Transitive-Conceptual-Overview
 * @author mattwigway
 */
public class TransitiveNetwork {
    public List<TransitiveRoute> routes = new ArrayList<>();
    public List<TransitiveStop> stops = new ArrayList<>();
    public List<TransitivePattern> patterns = new ArrayList<>();
    // places, journeys not currently supported - these are added by the client.

    private static Hex hex = new Hex();

    public TransitiveNetwork (TransitLayer layer, StreetLayer streetLayer) {
        // first write patterns, accumulating routes along the way
        TIntObjectMap<TransitiveRoute> routes = new TIntObjectHashMap<>();

        for (int pattIdx = 0; pattIdx < layer.tripPatterns.size(); pattIdx++) {
            TripPattern patt = layer.tripPatterns.get(pattIdx);

            if (!routes.containsKey(patt.routeIndex)) {
                // create the route
                // TODO save enough information to get rid of all of this boilerplate
                TransitiveRoute route = new TransitiveRoute();
                RouteInfo ri = layer.routes.get(patt.routeIndex);
                route.agency_id = ri.agency_id;
                route.route_short_name = ri.route_short_name;
                route.route_long_name = ri.route_long_name;
                route.route_id = patt.routeIndex + "";
                route.route_type = ri.route_type;
                route.route_color = ri.color;

                // Transitive always expects route short name to be defined, and the GTFS spec requires use of the empty
                // string when the field is empty. GTFS lib converts that to null, convert it back.
                if (route.route_long_name == null) route.route_long_name = "Route";
                if (route.route_short_name == null) route.route_short_name = route.route_long_name.split("[^A-Za-z0-9]")[0];

                routes.put(patt.routeIndex, route);
            }

            TransitivePattern tr = new TransitivePattern();
            // TODO boilerplate
            tr.pattern_id = pattIdx + "";
            tr.pattern_name = routes.get(patt.routeIndex).route_short_name;
            tr.route_id = patt.routeIndex + "";

            tr.stops = new ArrayList<>();

            if (patt.shape != null) {
                LocationIndexedLine unprojectedLine = new LocationIndexedLine(patt.shape);

                for (int stopPos = 0; stopPos < patt.stops.length; stopPos++) {

                    LineString geometry = null;

                    if (stopPos < patt.stops.length - 1) {
                        LinearLocation from =
                                new LinearLocation(patt.stopShapeSegment[stopPos], patt.stopShapeFraction[stopPos]);
                        LinearLocation to =
                                new LinearLocation(patt.stopShapeSegment[stopPos + 1], patt.stopShapeFraction[stopPos + 1]);
                        geometry = (LineString) unprojectedLine.extractLine(from, to);
                    }

                    tr.stops.add(new TransitivePattern.StopIdRef(Integer.toString(patt.stops[stopPos]), geometry));
                }

            } else {
                VertexStore.Vertex v = streetLayer.vertexStore.getCursor();

                Coordinate[] coords = IntStream.of(patt.stops)
                        .mapToObj(sidx -> {
                            v.seek(sidx);
                            return new Coordinate(v.getLon(), v.getLat());
                        })
                        .toArray(Coordinate[]::new);

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

                for (int stopPos = 0; stopPos < patt.stops.length; stopPos++) {
                    LineString geometry = null;

                    if (stopPos < patt.stops.length - 1) {
                        geometry = GeometryUtils.geometryFactory.createLineString(new Coordinate[] { coords[stopPos], coords[stopPos + 1] });
                    }
                    tr.stops.add(new TransitivePattern.StopIdRef(Integer.toString(patt.stops[stopPos]), geometry));
                }
            }

            patterns.add(tr);
        }

        this.routes.addAll(routes.valueCollection());

        VertexStore.Vertex v = layer.parentNetwork.streetLayer.vertexStore.getCursor();

        // write stops
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
}
