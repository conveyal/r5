package com.conveyal.analysis.controllers;

import com.conveyal.analysis.models.Bundle;
import com.conveyal.analysis.persistence.Persistence;
import com.conveyal.gtfs.GTFSCache;
import com.conveyal.gtfs.GTFSFeed;
import com.conveyal.gtfs.api.graphql.WrappedGTFSEntity;
import com.conveyal.gtfs.model.Pattern;
import com.conveyal.gtfs.model.Route;
import com.conveyal.gtfs.model.Stop;
import com.conveyal.gtfs.model.StopTime;
import com.conveyal.gtfs.model.Trip;
import org.mapdb.Fun;
import spark.Request;
import spark.Response;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import static com.conveyal.analysis.util.JsonUtil.toJson;

public class GTFSController implements HttpController {
    private final GTFSCache gtfsCache;
    public GTFSController (GTFSCache gtfsCache) {
        this.gtfsCache = gtfsCache;
    }

    private GTFSFeed getFeedFromRequest (Request req) {
        Bundle bundle = Persistence.bundles.findByIdFromRequestIfPermitted(req);
        String bundleScopedFeedId = Bundle.bundleScopeFeedId(req.params("feedId"), bundle.feedGroupId);
        return gtfsCache.get(bundleScopedFeedId);
    }

    static class RouteAPIResponse {
        public final String id;
        public final String name;
        public final int type;
        public final String color;

        RouteAPIResponse(Route route) {
            id = route.route_id;
            color = route.route_color;
            name = String.join(" ", route.route_short_name + "", route.route_long_name + "").trim();
            type = route.route_type;
        }
    }

    private List<RouteAPIResponse> getRoutes(Request req, Response res) {
        GTFSFeed feed = getFeedFromRequest(req);
        return feed.routes
                .values()
                .stream()
                .map(RouteAPIResponse::new)
                .collect(Collectors.toList());
    }

    static class PatternAPIResponse {
        public final String id;
        public final com.vividsolutions.jts.geom.LineString geometry;
        public final List<String> orderedStopIds;
        public final List<String> associatedTripIds;
        PatternAPIResponse(Pattern pattern) {
            id = pattern.pattern_id;
            geometry = pattern.geometry;
            orderedStopIds = pattern.orderedStops;
            associatedTripIds = pattern.associatedTrips;
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

    static class StopAPIResponse {
        public final String id;
        public final String name;
        public final double lat;
        public final double lon;

        StopAPIResponse(Stop stop) {
            id = stop.stop_id;
            name = stop.stop_name;
            lat = stop.stop_lat;
            lon = stop.stop_lon;
        }
    }

    private List<StopAPIResponse> getStops (Request req, Response res) {
        GTFSFeed feed = getFeedFromRequest(req);
        return feed.stops.values().stream().map(StopAPIResponse::new).collect(Collectors.toList());
    }

    static class FeedStopsAPIResponse {
        public final String feedId;
        public final List<StopAPIResponse> stops;

        FeedStopsAPIResponse(String feedId, List<StopAPIResponse> stops) {
            this.feedId = feedId;
            this.stops = stops;
        }
    }

    private List<FeedStopsAPIResponse> getBundleStops (Request req, Response res) {
        final Bundle bundle = Persistence.bundles.findByIdFromRequestIfPermitted(req);
        return bundle.feeds.stream().map(f -> {
            String bundleScopedFeedId = Bundle.bundleScopeFeedId(f.feedId, bundle.feedGroupId);
            GTFSFeed feed = gtfsCache.get(bundleScopedFeedId);
            List<StopAPIResponse> stops = feed.stops.values().stream().map(StopAPIResponse::new).collect(Collectors.toList());
            return new FeedStopsAPIResponse(
                    f.feedId,
                    stops
            );
        }).collect(Collectors.toList());
    }

    static class TripAPIResponse {
        public final String id;
        public final String name;
        public final String headsign;
        public final Integer startTime;
        public final Integer duration;
        public final int directionId;

        TripAPIResponse(GTFSFeed feed, Trip trip) {
            id = trip.trip_id;
            name = trip.trip_short_name;
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
        sparkService.get("/api/gtfs/:_id/stops", this::getBundleStops, toJson);
        sparkService.get("/api/gtfs/:_id/:feedId/routes", this::getRoutes, toJson);
        sparkService.get("/api/gtfs/:_id/:feedId/routes/:routeId/patterns", this::getPatternsForRoute, toJson);
        sparkService.get("/api/gtfs/:_id/:feedId/routes/:routeId/trips", this::getTripsForRoute, toJson);
        sparkService.get("/api/gtfs/:_id/:feedId/stops", this::getStops, toJson);
    }
}
