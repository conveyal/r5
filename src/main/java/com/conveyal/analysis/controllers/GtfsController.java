package com.conveyal.analysis.controllers;

import com.conveyal.analysis.AnalysisServerException;
import com.conveyal.analysis.models.Bundle;
import com.conveyal.analysis.persistence.Persistence;
import com.conveyal.analysis.util.LimitedPool;
import com.conveyal.analysis.util.VectorMapTile;
import com.conveyal.gtfs.GTFSCache;
import com.conveyal.gtfs.GTFSFeed;
import com.conveyal.gtfs.model.Pattern;
import com.conveyal.gtfs.model.Route;
import com.conveyal.gtfs.model.Stop;
import com.conveyal.gtfs.model.Trip;
import com.mongodb.QueryBuilder;
import org.locationtech.jts.geom.Geometry;
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

import static com.conveyal.analysis.util.HttpStatus.OK_200;
import static com.conveyal.analysis.util.HttpUtils.CACHE_CONTROL_IMMUTABLE;
import static com.conveyal.analysis.util.JsonUtil.toJson;

/**
 * Controller for retrieving contents of GTFS feeds that have been converted to MapDB files via the GTFS cache.
 *
 * Each endpoint starts with it's `feedGroupId` and `feedId` for retrieving the feed from the cache. No database
 * interaction is done. This assumes that if the user is logged in and retrieving the feed by the appropriate ID then
 * they have access to it, without checking the access group. This setup will allow for putting this endpoint behind a
 * CDN in the future. Everything retrieved is immutable. Once it's retrieved and stored in the CDN, it doesn't need to
 * be pulled from the cache again.
 *
 * Also defines HTTP API endpoints to return Mapbox vector tiles of GTFS feeds known to the Analysis backend.
 * A basic example client for browsing the tiles is at src/main/resources/vector-client
 */
public class GtfsController implements HttpController {

    // Vector tile layer names. These must match the layer names expected in the UI code.
    private static final String PATTERN_LAYER_NAME = "conveyal:gtfs:patternShapes";
    private static final String STOP_LAYER_NAME = "conveyal:gtfs:stops";

    private final GTFSCache gtfsCache;

    public GtfsController(GTFSCache gtfsCache) {
        this.gtfsCache = gtfsCache;
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

    private static String bundleScopedFeedIdFromRequest (Request req) {
        return Bundle.bundleScopeFeedId(req.params("feedId"), req.params("feedGroupId"));
    }

    private LimitedPool.Entry getFeedFromRequest (Request req) {
        return gtfsCache.get(bundleScopedFeedIdFromRequest(req));
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
        try (LimitedPool<String, GTFSFeed>.Entry feedEntry = getFeedFromRequest(req)) {
            GTFSFeed feed = feedEntry.value();
            return new RouteApiResponse(feed.routes.get(req.params("routeId")));
        }
    }

    private List<RouteApiResponse> getRoutes(Request req, Response res) {
        try (LimitedPool<String, GTFSFeed>.Entry feedEntry = getFeedFromRequest(req)) {
            GTFSFeed feed = feedEntry.value();
            return feed.routes.values().stream().map(RouteApiResponse::new).collect(Collectors.toList());
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
        try (LimitedPool<String, GTFSFeed>.Entry feedEntry = getFeedFromRequest(req)) {
            GTFSFeed feed = feedEntry.value();
            final String routeId = req.params("routeId");
            return feed.patterns.values().stream()
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
    /**
     * Return StopApiResponse values for GTFS stops (location_type = 0) in a single feed
     */
    private List<StopApiResponse> getAllStopsForOneFeed(Request req, Response res) {
        try (LimitedPool<String, GTFSFeed>.Entry feedEntry = getFeedFromRequest(req)) {
            GTFSFeed feed = feedEntry.value();
            return feed.stops.values().stream().filter(s -> s.location_type == 0)
                    .map(StopApiResponse::new).collect(Collectors.toList());
        }
    }

    /**
     * Groups the feedId and stops (location_type = 0; not parent stations, entrances/exits, generic nodes, etc.) for a
     * given GTFS feed
     */
    static class FeedGroupStopsApiResponse {
        public final String feedId;
        public final List<StopApiResponse> stops;

        FeedGroupStopsApiResponse(GTFSFeed feed) {
            this.feedId = feed.feedId;
            this.stops =
                    feed.stops.values().stream().filter(s -> s.location_type == 0).
                            map(StopApiResponse::new).collect(Collectors.toList());
        }
    }

    private List<FeedGroupStopsApiResponse> getAllStopsForFeedGroup(Request req, Response res) {
        String feedGroupId = req.params("feedGroupId");
        DBCursor<Bundle> cursor = Persistence.bundles.find(QueryBuilder.start("feedGroupId").is(feedGroupId).get());
        if (!cursor.hasNext()) {
            throw AnalysisServerException.notFound("Bundle could not be found for the given feed group ID.");
        }

        List<FeedGroupStopsApiResponse> allStopsByFeed = new ArrayList<>();
        Bundle bundle = cursor.next();
        for (Bundle.FeedSummary feedSummary : bundle.feeds) {
            String bundleScopedFeedId = Bundle.bundleScopeFeedId(feedSummary.feedId, feedGroupId);
            try (LimitedPool<String, GTFSFeed>.Entry feedEntry = gtfsCache.get(bundleScopedFeedId)) {
                GTFSFeed feed = feedEntry.value();
                allStopsByFeed.add(new FeedGroupStopsApiResponse(feed));
            }
        }
        return allStopsByFeed;
    }

    /**
     * Generate a Mapbox Vector Tile of a GTFS feed's stops and pattern shapes for a given Z/X/Y tile.
     */
    private Object getTile (Request req, Response res) {
        String bundleScopedFeedId = bundleScopedFeedIdFromRequest(req);
        final int z = Integer.parseInt(req.params("z"));
        final int x = Integer.parseInt(req.params("x"));
        final int y = Integer.parseInt(req.params("y"));
        VectorMapTile tile = new VectorMapTile(z, x, y);
        List<Geometry> patternGeometries = tile.clipAndSimplifyLinesToTile(
                gtfsCache.patternShapes.queryEnvelope(bundleScopedFeedId, tile.envelope)
        );
        List<Geometry> stopGeometries = tile.projectPointsToTile(
                gtfsCache.stops.queryEnvelope(bundleScopedFeedId, tile.envelope)
        );

        res.header("Content-Type", "application/vnd.mapbox-vector-tile");
        res.header("Content-Encoding", "gzip");
        res.status(OK_200);

        return tile.encodeLayersToBytes(
                tile.createLayer(PATTERN_LAYER_NAME, patternGeometries),
                tile.createLayer(STOP_LAYER_NAME, stopGeometries)
        );
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
        try (LimitedPool<String, GTFSFeed>.Entry feedEntry = getFeedFromRequest(req)) {
            GTFSFeed feed = feedEntry.value();
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
    public void registerEndpoints (spark.Service ss) {
        ss.path("/api/gtfs/:feedGroupId", () -> {
            ss.before("*", (req, res) ->{
                res.header("Cache-Control", CACHE_CONTROL_IMMUTABLE);
            });
            ss.get("/stops", this::getAllStopsForFeedGroup, toJson);
            ss.path("/:feedId", () -> {
                ss.get("/tiles/:z/:x/:y", this::getTile);

                ss.path("/routes", () -> {
                    ss.get("", this::getRoutes, toJson);
                    ss.path("/:routeId", () -> {
                        ss.get("", this::getRoute, toJson);
                        ss.get("/patterns", this::getPatternsForRoute, toJson);
                        ss.get("/trips", this::getTripsForRoute, toJson);
                    });
                });

                ss.get("/stops", this::getAllStopsForOneFeed, toJson);
            });
        });
    }
}
