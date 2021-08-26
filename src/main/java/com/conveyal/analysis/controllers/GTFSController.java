package com.conveyal.analysis.controllers;

import com.conveyal.analysis.models.Bundle;
import com.conveyal.gtfs.GTFSCache;
import com.conveyal.gtfs.GTFSFeed;
import com.conveyal.gtfs.model.Pattern;
import com.conveyal.gtfs.model.Route;
import com.conveyal.gtfs.model.Stop;
import com.conveyal.gtfs.model.StopTime;
import com.conveyal.gtfs.model.Trip;
import org.mapdb.Fun;
import spark.Request;
import spark.Response;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.conveyal.analysis.util.JsonUtil.toJson;

public class GTFSController implements HttpController {
    private final GTFSCache gtfsCache;
    public GTFSController (GTFSCache gtfsCache) {
        this.gtfsCache = gtfsCache;
    }

    private static class BaseIdentifier {
        public final String _id;
        public final String name;

        BaseIdentifier (String _id, String name) {
            this._id = _id;
            this.name = name;
        }
    }

    private static class GeoJSONLineString {
        public final String type = "LineString";
        public double[][] coordinates;
    }

    private GTFSFeed getFeedFromRequest (Request req) {
        String bundleScopedFeedId = Bundle.bundleScopeFeedId(req.params("feedId"), req.params("feedGroupId"));
        return gtfsCache.get(bundleScopedFeedId);
    }

    static class RouteAPIResponse extends BaseIdentifier {
        public final int type;
        public final String color;

        static String getRouteName (Route route) {
            String tempName = "";
            if (route.route_short_name != null) tempName += route.route_short_name;
            if (route.route_long_name != null) tempName += " " + route.route_long_name;
            return tempName.trim();
        }

        RouteAPIResponse(Route route) {
            super(route.route_id, getRouteName(route));
            color = route.route_color;
            type = route.route_type;
        }
    }

    private RouteAPIResponse getRoute(Request req, Response res) {
        GTFSFeed feed = getFeedFromRequest(req);
        return new RouteAPIResponse(feed.routes.get(req.params("routeId")));
    }

    private List<RouteAPIResponse> getRoutes(Request req, Response res) {
        GTFSFeed feed = getFeedFromRequest(req);
        return feed.routes
                .values()
                .stream()
                .map(RouteAPIResponse::new)
                .collect(Collectors.toList());
    }

    static class PatternAPIResponse extends BaseIdentifier {
        public final GeoJSONLineString geometry;
        public final List<String> orderedStopIds;
        public final List<String> associatedTripIds;

        PatternAPIResponse(Pattern pattern) {
            super(pattern.pattern_id, pattern.name);
            geometry = serialize(pattern.geometry);
            orderedStopIds = pattern.orderedStops;
            associatedTripIds = pattern.associatedTrips;
        }

        static GeoJSONLineString serialize (com.vividsolutions.jts.geom.LineString geometry) {
            GeoJSONLineString ret = new GeoJSONLineString();
            ret.coordinates = Stream.of(geometry.getCoordinates())
                    .map(c -> new double[] { c.x, c.y })
                    .toArray(double[][]::new);

            return ret;
        }
    }

    private List<PatternAPIResponse> getPatternsForRoute (Request req, Response res) {
        GTFSFeed feed = getFeedFromRequest(req);
        final String routeId = req.params("routeId");
        return feed.patterns
                .values()
                .stream()
                .filter(p -> Objects.equals(p.route_id, routeId))
                .map(PatternAPIResponse::new)
                .collect(Collectors.toList());
    }

    static class StopAPIResponse extends BaseIdentifier {
        public final double lat;
        public final double lon;

        StopAPIResponse(Stop stop) {
            super(stop.stop_id, stop.stop_name);
            lat = stop.stop_lat;
            lon = stop.stop_lon;
        }
    }

    private List<StopAPIResponse> getStops (Request req, Response res) {
        GTFSFeed feed = getFeedFromRequest(req);
        return feed.stops.values().stream().map(StopAPIResponse::new).collect(Collectors.toList());
    }

    static class TripAPIResponse extends BaseIdentifier {
        public final String headsign;
        public final Integer startTime;
        public final Integer duration;
        public final int directionId;

        TripAPIResponse(GTFSFeed feed, Trip trip) {
            super(trip.trip_id, trip.trip_short_name);
            headsign = trip.trip_headsign;
            directionId = trip.direction_id;

            Map.Entry<Fun.Tuple2, StopTime> st = feed.stop_times.ceilingEntry(new Fun.Tuple2(trip.trip_id, null));
            Map.Entry<Fun.Tuple2, StopTime> endStopTime = feed.stop_times.floorEntry(new Fun.Tuple2(trip.trip_id, Fun.HI));

            startTime = st != null ? st.getValue().departure_time : null;

            if (startTime == null || endStopTime == null || endStopTime.getValue().arrival_time < startTime) {
                duration = null;
            } else {
                duration = endStopTime.getValue().arrival_time - startTime;
            }
        }
    }

    private List<TripAPIResponse> getTripsForRoute (Request req, Response res) {
        final GTFSFeed feed = getFeedFromRequest(req);
        final String routeId = req.params("routeId");
        return feed.trips
                .values().stream()
                .filter(t -> Objects.equals(t.route_id, routeId))
                .map(t -> new TripAPIResponse(feed, t))
                .sorted(Comparator.comparingInt(t -> t.startTime))
                .collect(Collectors.toList());
    }

    @Override
    public void registerEndpoints (spark.Service sparkService) {
        sparkService.get("/api/gtfs/:feedGroupId/:feedId/routes", this::getRoutes, toJson);
        sparkService.get("/api/gtfs/:feedGroupId/:feedId/routes/:routeId", this::getRoute, toJson);
        sparkService.get("/api/gtfs/:feedGroupId/:feedId/routes/:routeId/patterns", this::getPatternsForRoute, toJson);
        sparkService.get("/api/gtfs/:feedGroupId/:feedId/routes/:routeId/trips", this::getTripsForRoute, toJson);
        sparkService.get("/api/gtfs/:feedGroupId/:feedId/stops", this::getStops, toJson);
    }
}
