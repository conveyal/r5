package com.conveyal.r5.analyst.network;

import com.conveyal.gtfs.GTFSFeed;
import com.conveyal.gtfs.model.Agency;
import com.conveyal.gtfs.model.CalendarDate;
import com.conveyal.gtfs.model.Frequency;
import com.conveyal.gtfs.model.Route;
import com.conveyal.gtfs.model.Service;
import com.conveyal.gtfs.model.Stop;
import com.conveyal.gtfs.model.StopTime;
import com.conveyal.gtfs.model.Trip;
import org.mapdb.Fun;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

import static com.conveyal.r5.analyst.network.GridRoute.Materialization.EXACT_TIMES;
import static com.conveyal.r5.analyst.network.GridRoute.Materialization.STOP_TIMES;

/**
 * Create a MapDB backed GTFS object from a GridLayout, not necessarily to be written out as a standard CSV/ZIP feed,
 * but to be fed directly into the R5 network builder. Used to create networks with predictable characteristics in tests.
 */
public class GridGtfsGenerator {

    public static final String FEED_ID = "GRID";
    public static final String AGENCY_ID = "AGENCY";

    public static final LocalDate WEEKDAY_DATE = LocalDate.of(2020, 1, 1);
    public static final LocalDate WEEKEND_DATE = LocalDate.of(2020, 1, 4);

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
        addService(GridRoute.Services.WEEKDAY, 1, 2, 3);
        addService(GridRoute.Services.WEEKEND, 4, 5);
        addService(GridRoute.Services.ALL, 1, 2, 3, 4, 5);
    }

    private void addService (GridRoute.Services grs, int... daysOfJanuary2020) {
        Service gtfsService = new Service(grs.name());
        for (int day : daysOfJanuary2020) {
            CalendarDate calendarDate = new CalendarDate();
            calendarDate.date = LocalDate.of(2020, 01, day);
            calendarDate.service_id = gtfsService.service_id;
            calendarDate.exception_type = 1;
            gtfsService.calendar_dates.put(calendarDate.date, calendarDate);
        }
        feed.services.put(gtfsService.service_id, gtfsService);
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
        // An explicit array of trip start times takes precedence over timetables.
        if (route.startTimes != null) {
            for (int i = 0; i < route.startTimes.length; i++) {
                addTrip(route, back, route.startTimes[i], i, GridRoute.Services.ALL);
            }
            return;
        }
        // For the non-STOP_TIMES case, a single trip per service that will be referenced by all the timetables.
        // We should somehow also allow for different travel speeds per timetable, and default fallback speeds.
        Map<GridRoute.Services, String> tripIdForService = new HashMap<>();
        int tripIndex = 0;
        for (GridRoute.Timetable timetable : route.timetables) {
            int start = timetable.startHour * 60 * 60;
            int end = timetable.endHour * 60 * 60;
            int headway = timetable.headwayMinutes * 60;
            if (route.materialization == STOP_TIMES) {
                // For STOP_TIMES, make N different trips.
                for (int startTime = start; startTime < end; startTime += headway, tripIndex++) {
                    addTrip(route, back, startTime, tripIndex, timetable.service);
                }
            } else {
                // Not STOP_TIMES, so this is a frequency entry (either EXACT_TIMES or PURE_FREQ).
                // Make only one trip per service ID, all frequency entries reference this single trip.
                String tripId = tripIdForService.get(timetable.service);
                if (tripId == null) {
                    tripId = addTrip(route, back, 0, tripIndex, timetable.service);
                    tripIdForService.put(timetable.service, tripId);
                    tripIndex++;
                }
                Frequency frequency = new Frequency();
                frequency.start_time = start;
                frequency.end_time = end;
                frequency.headway_secs = headway;
                frequency.exact_times = (route.materialization == EXACT_TIMES) ? 1 : 0;
                feed.frequencies.add(new Fun.Tuple2<>(tripId, frequency));
            }
        }
    }

    private String addTrip (GridRoute route, boolean back, int startTime, int tripIndex, GridRoute.Services service) {
        Trip trip = new Trip();
        trip.direction_id = back ? 1 : 0;
        String tripId = String.format("%s:%d:%d", route.id, tripIndex, trip.direction_id);
        trip.trip_id = tripId;
        trip.route_id = route.id;
        trip.service_id = service.name();
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
