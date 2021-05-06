package com.conveyal.r5.analyst.network;

import com.conveyal.gtfs.GTFSFeed;
import com.conveyal.gtfs.model.Agency;
import com.conveyal.gtfs.model.Calendar;
import com.conveyal.gtfs.model.CalendarDate;
import com.conveyal.gtfs.model.Frequency;
import com.conveyal.gtfs.model.Route;
import com.conveyal.gtfs.model.Service;
import com.conveyal.gtfs.model.Stop;
import com.conveyal.gtfs.model.StopTime;
import com.conveyal.gtfs.model.Trip;
import org.checkerframework.checker.units.qual.A;
import org.mapdb.Fun;

import java.time.LocalDate;
import java.util.stream.IntStream;

/**
 * Create a MapDB backed GTFS object from a GridLayout, not necessarily to be written out as a standard CSV/ZIP feed,
 * but to be fed directly into the R5 network builder. Used to create networks with predictable characteristics in tests.
 */
public class GridGtfsGenerator {

    public static final String FEED_ID = "GRID";
    public static final String AGENCY_ID = "AGENCY";
    public static final String SERVICE_ID = "ALL";

    public static final LocalDate GTFS_DATE = LocalDate.of(2020, 1, 1);

    public final GridLayout gridLayout;

    private final GTFSFeed feed;

    private final boolean mergeStops;

    public GridGtfsGenerator (GridLayout gridLayout) {
        this.gridLayout = gridLayout;
        feed = GTFSFeed.newWritableInMemory();
        mergeStops = true;
    }

    public GTFSFeed generate () {
        for (GridRoute route : gridLayout.routes) {
            addRoute(route);
        }
        addCommonTables();
        feed.findPatterns();
        return feed;
    }

    private void addCommonTables () {
        Agency agency = new Agency();
        agency.agency_id = AGENCY_ID;
        agency.agency_name = AGENCY_ID;
        feed.agency.put(agency.agency_id, agency);

        Service service = new Service(SERVICE_ID);
        CalendarDate calendarDate = new CalendarDate();
        calendarDate.date = LocalDate.of(2020, 01, 01);
        calendarDate.service_id = SERVICE_ID;
        calendarDate.exception_type = 1;
        service.calendar_dates.put(calendarDate.date, calendarDate);
        feed.services.put(service.service_id, service);
    }

    private void addRoute (GridRoute gridRoute) {
        Route route = new Route();
        route.agency_id = AGENCY_ID;
        route.route_id = gridRoute.id;
        route.route_short_name = gridRoute.id;
        route.route_type = 2;
        feed.routes.put(route.route_id, route);

        addRoute(gridRoute, false);
        if (gridRoute.bidirectional) {
            addRoute(gridRoute, true);
        }
    }

    private void addRoute (GridRoute route, boolean back) {
        addStopsForRoute(route, back);
        addTripsForRoute(route, back);
    }

    // If mergeStops is true, certain stops will be created multiple times but IDs will collide on insertion.
    public void addStopsForRoute (GridRoute route, boolean back) {
        for (int s = 0; s < route.nStops; s++) {
            String stopId = route.stopId(s, mergeStops);
            Stop stop = new Stop();
            stop.location_type = 0;
            stop.stop_id = stopId;
            stop.stop_name = stopId;
            stop.stop_lat = route.getStopLat(s);
            stop.stop_lon = route.getStopLon(s);
            feed.stops.put(stopId, stop);
        }
    }

    public void addTripsForRoute (GridRoute route, boolean back) {
        if (route.headwayMinutes > 0) {
            int tripIndex = 0;
            int start = route.startHour * 60 * 60;
            int end = route.endHour * 60 * 60;
            int headway = route.headwayMinutes * 60;

            // Maybe we should use exact_times = 1 instead of generating individual trips.
            for (int startTime = start; startTime < end; startTime += headway, tripIndex++) {
                String tripId = addTrip(route, back, startTime, tripIndex);
                if (route.pureFrequency) {
                    Frequency frequency = new Frequency();
                    frequency.start_time = start;
                    frequency.end_time = end;
                    frequency.headway_secs = headway;
                    frequency.exact_times = 0;
                    feed.frequencies.add(new Fun.Tuple2<>(tripId, frequency));
                    // Do not make any additional trips, frequency entry represents them.
                    break;
                }
            }
        } else {
            for (int i = 0; i < route.startTimes.length; i++) {
                addTrip(route, back, route.startTimes[i], i);
            }
        }
    }

    private String addTrip (GridRoute route, boolean back, int startTime, int tripIndex) {
        Trip trip = new Trip();
        trip.direction_id = back ? 1 : 0;
        String tripId = String.format("%s:%d:%d", route.id, tripIndex, trip.direction_id);
        trip.trip_id = tripId;
        trip.route_id = route.id;
        trip.service_id = SERVICE_ID;
        feed.trips.put(trip.trip_id, trip);
        int dwell = gridLayout.transitDwellSeconds;
        int departureTime = startTime;
        int arrivalTime = departureTime - dwell;
        for (int stopSequence = 0; stopSequence < route.nStops; stopSequence++) {
            int stopInRoute = back ? route.nStops - 1 - stopSequence : stopSequence;
            StopTime stopTime = new StopTime();
            stopTime.stop_id = route.stopId(stopInRoute, mergeStops);
            stopTime.stop_sequence = stopSequence;
            stopTime.arrival_time = arrivalTime;
            stopTime.departure_time = departureTime;
            stopTime.trip_id = tripId;
            feed.stop_times.put(new Fun.Tuple2<>(tripId, stopTime.stop_sequence), stopTime);
            if (stopSequence < route.nStops - 1) {
                int hopTime = (int) route.hopTime(new GridRoute.TripHop(tripIndex, stopSequence));
                arrivalTime += hopTime;
                departureTime += hopTime;
            }
        }
        return tripId;
    }

}
