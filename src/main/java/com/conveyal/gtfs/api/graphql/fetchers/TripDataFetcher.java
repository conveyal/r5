package com.conveyal.gtfs.api.graphql.fetchers;

import com.conveyal.gtfs.GTFSFeed;
import com.conveyal.gtfs.api.ApiMain;
import com.conveyal.gtfs.api.graphql.WrappedGTFSEntity;
import com.conveyal.gtfs.model.Agency;
import com.conveyal.gtfs.model.Pattern;
import com.conveyal.gtfs.model.Route;
import com.conveyal.gtfs.model.StopTime;
import com.conveyal.gtfs.model.Trip;
import graphql.schema.DataFetchingEnvironment;
import org.mapdb.Fun;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static spark.Spark.halt;

public class TripDataFetcher {
    public static List<WrappedGTFSEntity<Trip>> apex(DataFetchingEnvironment env) {
        Collection<GTFSFeed> feeds;

        List<String> feedId = (List<String>) env.getArgument("feed_id");
        feeds = ApiMain.getFeedSources(feedId);

        List<WrappedGTFSEntity<Trip>> trips = new ArrayList<>();

        // TODO: clear up possible scope issues feed and trip IDs
        for (GTFSFeed feed : feeds) {
            if (env.getArgument("trip_id") != null) {
                List<String> tripId = (List<String>) env.getArgument("trip_id");
                tripId.stream()
                        .filter(feed.trips::containsKey)
                        .map(feed.trips::get)
                        .map(trip -> new WrappedGTFSEntity(feed.uniqueId, trip))
                        .forEach(trips::add);
            }
            else if (env.getArgument("route_id") != null) {
                List<String> routeId = (List<String>) env.getArgument("route_id");
                feed.trips.values().stream()
                        .filter(t -> routeId.contains(t.route_id))
                        .map(trip -> new WrappedGTFSEntity(feed.uniqueId, trip))
                        .forEach(trips::add);
            }
            else {
                feed.trips.values().stream()
                        .map(trip -> new WrappedGTFSEntity(feed.uniqueId, trip))
                        .forEach(trips::add);
            }
        }

        return trips;
    }

    /**
     * Fetch trip data given a route.
     */
    public static List<WrappedGTFSEntity<Trip>> fromRoute(DataFetchingEnvironment dataFetchingEnvironment) {
        WrappedGTFSEntity<Route> route = (WrappedGTFSEntity<Route>) dataFetchingEnvironment.getSource();
        GTFSFeed feed = ApiMain.getFeedSourceWithoutExceptions(route.feedUniqueId);
        if (feed == null) return null;

        return feed.trips.values().stream()
                .filter(t -> t.route_id.equals(route.entity.route_id))
                .map(t -> new WrappedGTFSEntity<>(feed.uniqueId, t))
                .collect(Collectors.toList());
    }

    public static Long fromRouteCount(DataFetchingEnvironment dataFetchingEnvironment) {
        WrappedGTFSEntity<Route> route = (WrappedGTFSEntity<Route>) dataFetchingEnvironment.getSource();
        GTFSFeed feed = ApiMain.getFeedSourceWithoutExceptions(route.feedUniqueId);
        if (feed == null) return null;

        return feed.trips.values().stream()
                .filter(t -> t.route_id.equals(route.entity.route_id))
                .count();
    }

    public static WrappedGTFSEntity<Trip> fromStopTime (DataFetchingEnvironment env) {
        WrappedGTFSEntity<StopTime> stopTime = (WrappedGTFSEntity<StopTime>) env.getSource();
        GTFSFeed feed = ApiMain.getFeedSourceWithoutExceptions(stopTime.feedUniqueId);
        if (feed == null) return null;

        Trip trip = feed.trips.get(stopTime.entity.trip_id);

        return new WrappedGTFSEntity<>(stopTime.feedUniqueId, trip);
    }

    public static List<WrappedGTFSEntity<Trip>> fromPattern (DataFetchingEnvironment env) {
        WrappedGTFSEntity<Pattern> pattern = (WrappedGTFSEntity<Pattern>) env.getSource();
        GTFSFeed feed = ApiMain.getFeedSourceWithoutExceptions(pattern.feedUniqueId);
        if (feed == null) return null;

        Long beginTime = env.getArgument("begin_time");
        Long endTime = env.getArgument("end_time");

        if (beginTime != null && endTime != null) {
            String agencyId = feed.routes.get(pattern.entity.route_id).agency_id;
            Agency agency = agencyId != null ? feed.agency.get(agencyId) : null;
            if (beginTime >= endTime) {
                halt(404, "end_time must be greater than begin_time.");
            }
            LocalDateTime beginDateTime = LocalDateTime.ofEpochSecond(beginTime, 0, ZoneOffset.UTC);
            int beginSeconds = beginDateTime.getSecond();
            LocalDateTime endDateTime = LocalDateTime.ofEpochSecond(endTime, 0, ZoneOffset.UTC);
            int endSeconds = endDateTime.getSecond();
            long days = ChronoUnit.DAYS.between(beginDateTime, endDateTime);
            ZoneId zone =  agency != null ? ZoneId.of(agency.agency_timezone) : ZoneId.systemDefault();
            Set<String> services = feed.services.values().stream()
                    .filter(s -> {
                        for (int i = 0; i < days; i++) {
                            LocalDate date = beginDateTime.toLocalDate().plusDays(i);
                            if (s.activeOn(date)) {
                                return true;
                            }
                        }
                        return false;
                    })
                    .map(s -> s.service_id)
                    .collect(Collectors.toSet());
            return pattern.entity.associatedTrips.stream().map(feed.trips::get)
                    .filter(t -> services.contains(t.service_id))
                    .map(t -> new WrappedGTFSEntity<>(feed.uniqueId, t))
                    .collect(Collectors.toList());
        }
        else {
            return pattern.entity.associatedTrips.stream().map(feed.trips::get)
                    .map(t -> new WrappedGTFSEntity<>(feed.uniqueId, t))
                    .collect(Collectors.toList());
        }
    }

    public static Long fromPatternCount (DataFetchingEnvironment env) {
        WrappedGTFSEntity<Pattern> pattern = (WrappedGTFSEntity<Pattern>) env.getSource();

        GTFSFeed feed = ApiMain.getFeedSourceWithoutExceptions(pattern.feedUniqueId);
        if (feed == null) return null;

        return pattern.entity.associatedTrips.stream().map(feed.trips::get).count();
    }

    public static Integer getStartTime(DataFetchingEnvironment env) {
        WrappedGTFSEntity<Trip> trip = (WrappedGTFSEntity<Trip>) env.getSource();
        GTFSFeed feed = ApiMain.getFeedSourceWithoutExceptions(trip.feedUniqueId);
        if (feed == null) return null;

        Map.Entry<Fun.Tuple2, StopTime> st = feed.stop_times.ceilingEntry(new Fun.Tuple2(trip.entity.trip_id, null));
        return st != null ? st.getValue().departure_time : null;
    }

    public static Integer getDuration(DataFetchingEnvironment env) {
        WrappedGTFSEntity<Trip> trip = (WrappedGTFSEntity<Trip>) env.getSource();
        GTFSFeed feed = ApiMain.getFeedSourceWithoutExceptions(trip.feedUniqueId);
        if (feed == null) return null;

        Integer startTime = getStartTime(env);
        Map.Entry<Fun.Tuple2, StopTime> endStopTime = feed.stop_times.floorEntry(new Fun.Tuple2(trip.entity.trip_id, Fun.HI));

        if (startTime == null || endStopTime == null || endStopTime.getValue().arrival_time < startTime) return null;
        else return endStopTime.getValue().arrival_time - startTime;
    }
}
