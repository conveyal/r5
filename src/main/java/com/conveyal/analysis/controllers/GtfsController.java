package com.conveyal.analysis.controllers;

import com.conveyal.analysis.AnalysisServerException;
import com.conveyal.analysis.models.Bundle;
import com.conveyal.analysis.persistence.Persistence;
import com.conveyal.gtfs.GTFSCache;
import com.conveyal.gtfs.GTFSFeed;
import com.conveyal.gtfs.model.Pattern;
import com.conveyal.gtfs.model.Route;
import com.conveyal.gtfs.model.Stop;
import com.conveyal.gtfs.model.Trip;
import com.mongodb.QueryBuilder;
import org.mapdb.Fun;
import org.mongojack.DBCursor;
import spark.Request;
import spark.Response;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.conveyal.analysis.util.JsonUtil.toJson;

/**
 * Controller for retrieving data from the GTFS cache.
 *
 * Each endpoint starts with it's `feedGroupId` and `feedId` for retrieving the feed from the cache. No database
 * interaction is done. This assumes that if the user is logged in and retrieving the feed by the appropriate ID then
 * they have access to it, without checking the access group. This setup will allow for putting this endpoint behind a
 * CDN in the future. Everything retrieved is immutable. Once it's retrieved and stored in the CDN, it doesn't need to
 * be pulled from the cache again.
 */
public class GtfsController implements HttpController {
    private final GTFSCache gtfsCache;
    public GtfsController(GTFSCache gtfsCache) {
        this.gtfsCache = gtfsCache;
    }

    /**
     * Use the same Cache-Control header for each endpoint here. 2,592,000 seconds is one month.
     * https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/Cache-Control
     */
    private final String cacheControlImmutable = "public, max-age=2592000, immutable";

    /**
     * Extracted into a common method to allow turning off during development.
     */
    private void addImmutableResponseHeader (Response res) {
        res.header("Cache-Control", cacheControlImmutable);
    }

    private static class BaseApiResponse {
        public final String _id;
        public final String name;

        BaseApiResponse(String _id, String name) {
            this._id = _id;
            this.name = name;
        }
    }

    private static class GeoJsonLineString {
        public final String type = "LineString";
        public double[][] coordinates;
    }

    private GTFSFeed getFeedFromRequest (Request req) {
        String bundleScopedFeedId = Bundle.bundleScopeFeedId(req.params("feedId"), req.params("feedGroupId"));
        // return gtfsCache.get(bundleScopedFeedId);
        return gtfsCache.getBypassingCache(bundleScopedFeedId);
    }

    static class RouteApiResponse extends BaseApiResponse {
        public final int type;
        public final String color;

        static String getRouteName (Route route) {
            String tempName = "";
            if (route.route_short_name != null) tempName += route.route_short_name;
            if (route.route_long_name != null) tempName += " " + route.route_long_name;
            return tempName.trim();
        }

        RouteApiResponse(Route route) {
            super(route.route_id, getRouteName(route));
            color = route.route_color;
            type = route.route_type;
        }
    }

    private RouteApiResponse getRoute(Request req, Response res) {
        addImmutableResponseHeader(res);
        try (GTFSFeed feed = getFeedFromRequest(req)) {
            return new RouteApiResponse(feed.routes.get(req.params("routeId")));
        }
    }

    private List<RouteApiResponse> getRoutes(Request req, Response res) {
        addImmutableResponseHeader(res);
        try (GTFSFeed feed = getFeedFromRequest(req)) {
            return feed.routes
                    .values()
                    .stream()
                    .map(RouteApiResponse::new)
                    .collect(Collectors.toList());
        }
    }

    static class PatternApiResponse extends BaseApiResponse {
        public final GeoJsonLineString geometry;
        public final List<String> orderedStopIds;
        public final List<String> associatedTripIds;

        PatternApiResponse(Pattern pattern) {
            super(pattern.pattern_id, pattern.name);
            geometry = serialize(pattern.geometry);
            orderedStopIds = pattern.orderedStops;
            associatedTripIds = pattern.associatedTrips;
        }

        static GeoJsonLineString serialize (com.vividsolutions.jts.geom.LineString geometry) {
            GeoJsonLineString ret = new GeoJsonLineString();
            ret.coordinates = Stream.of(geometry.getCoordinates())
                    .map(c -> new double[] { c.x, c.y })
                    .toArray(double[][]::new);

            return ret;
        }
    }

    private List<PatternApiResponse> getPatternsForRoute (Request req, Response res) {
        addImmutableResponseHeader(res);
        try (GTFSFeed feed = getFeedFromRequest(req)) {
            final String routeId = req.params("routeId");
            return feed.patterns
                    .values()
                    .stream()
                    .filter(p -> Objects.equals(p.route_id, routeId))
                    .map(PatternApiResponse::new)
                    .collect(Collectors.toList());
        }
    }

    static class StopApiResponse extends BaseApiResponse {
        public final double lat;
        public final double lon;

        StopApiResponse(Stop stop) {
            super(stop.stop_id, stop.stop_name);
            lat = stop.stop_lat;
            lon = stop.stop_lon;
        }
    }

    private List<StopApiResponse> getAllStopsForOneFeed(Request req, Response res) {
        addImmutableResponseHeader(res);
        try (GTFSFeed feed = getFeedFromRequest(req)) {
            return feed.stops.values().stream().map(StopApiResponse::new).collect(Collectors.toList());
        }
    }

    static class FeedGroupStopsApiResponse {
        public final String feedId;
        public final List<StopApiResponse> stops;

        FeedGroupStopsApiResponse(GTFSFeed feed) {
            this.feedId = feed.feedId;
            this.stops = feed.stops.values().stream().map(StopApiResponse::new).collect(Collectors.toList());
        }
    }

    private List<FeedGroupStopsApiResponse> getAllStopsForFeedGroup(Request req, Response res) {
        addImmutableResponseHeader(res);
        String feedGroupId = req.params("feedGroupId");
        DBCursor<Bundle> cursor = Persistence.bundles.find(QueryBuilder.start("feedGroupId").is(feedGroupId).get());
        if (!cursor.hasNext()) {
            throw AnalysisServerException.notFound("Bundle could not be found for the given feed group ID.");
        }

        List<FeedGroupStopsApiResponse> allStopsByFeed = new ArrayList<>();
        Bundle bundle = cursor.next();
        for (Bundle.FeedSummary feedSummary : bundle.feeds) {
            String bundleScopedFeedId = Bundle.bundleScopeFeedId(feedSummary.feedId, feedGroupId);
            GTFSFeed feed = gtfsCache.get(bundleScopedFeedId);
            allStopsByFeed.add(new FeedGroupStopsApiResponse(feed));
        }
        return allStopsByFeed;
    }

    static class TripApiResponse extends BaseApiResponse {
        public final String headsign;
        public final Integer startTime;
        public final Integer duration;
        public final int directionId;

        TripApiResponse(GTFSFeed feed, Trip trip) {
            super(trip.trip_id, trip.trip_short_name);
            headsign = trip.trip_headsign;
            directionId = trip.direction_id;

            var st = feed.stop_times.ceilingEntry(new Fun.Tuple2(trip.trip_id, null));
            var endStopTime = feed.stop_times.floorEntry(new Fun.Tuple2(trip.trip_id, Fun.HI));

            startTime = st != null ? st.getValue().departure_time : null;

            if (startTime == null || endStopTime == null || endStopTime.getValue().arrival_time < startTime) {
                duration = null;
            } else {
                duration = endStopTime.getValue().arrival_time - startTime;
            }
        }
    }

    private List<TripApiResponse> getTripsForRoute (Request req, Response res) {
        addImmutableResponseHeader(res);
        try (GTFSFeed feed = getFeedFromRequest(req)) {
            final String routeId = req.params("routeId");
            return feed.trips
                    .values().stream()
                    .filter(t -> Objects.equals(t.route_id, routeId))
                    .map(t -> new TripApiResponse(feed, t))
                    .sorted(Comparator.comparingInt(t -> t.startTime))
                    .collect(Collectors.toList());
        }
    }

    @Override
    public void registerEndpoints (spark.Service sparkService) {
        sparkService.get("/api/gtfs/:feedGroupId/stops", this::getAllStopsForFeedGroup, toJson);
        sparkService.get("/api/gtfs/:feedGroupId/:feedId/routes", this::getRoutes, toJson);
        sparkService.get("/api/gtfs/:feedGroupId/:feedId/routes/:routeId", this::getRoute, toJson);
        sparkService.get("/api/gtfs/:feedGroupId/:feedId/routes/:routeId/patterns", this::getPatternsForRoute, toJson);
        sparkService.get("/api/gtfs/:feedGroupId/:feedId/routes/:routeId/trips", this::getTripsForRoute, toJson);
        sparkService.get("/api/gtfs/:feedGroupId/:feedId/stops", this::getAllStopsForOneFeed, toJson);
    }
}
