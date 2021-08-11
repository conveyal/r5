package com.conveyal.gtfs;

import com.conveyal.gtfs.model.Frequency;
import com.conveyal.gtfs.model.Route;
import com.conveyal.gtfs.model.Stop;
import com.conveyal.gtfs.model.StopTime;
import com.conveyal.gtfs.model.Transfer;
import com.conveyal.gtfs.model.Trip;
import com.google.common.base.Strings;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.mapdb.Fun;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * This main method will filter an input GTFS file, retaining only the given route_type (mode of transport).
 * All routes, trips, stops, and frequencies for other route_types will be removed. This is useful for preparing
 * minimal GTFS inputs for tests. For example, we have extracted only the subway / metro routes from the STM
 * Montreal feed - they are useful for testing Monte Carlo code because they have many frequency entries per trip.
 */
public class ExtractGTFSMode {

    private static final String inputFile = "/Users/abyrd/geodata/stm.gtfs.zip";
    private static final String outputFile = "/Users/abyrd/geodata/stm-metro.gtfs.zip";

    // Remove all shapes from the GTFS to make it simpler to render in a web UI
    private static final boolean REMOVE_SHAPES = true;

    private static final int RETAIN_ROUTE_TYPE = Route.SUBWAY;

    public static void main (String[] args) {

        GTFSFeed feed = GTFSFeed.writableTempFileFromGtfs(inputFile);

        System.out.println("Removing routes that are not on mode " + RETAIN_ROUTE_TYPE);
        Set<String> retainRoutes = new HashSet<>();
        Iterator<Route> routeIterator = feed.routes.values().iterator();
        while (routeIterator.hasNext()) {
            Route route = routeIterator.next();
            if (route.route_type == RETAIN_ROUTE_TYPE) {
                retainRoutes.add(route.route_id);
            } else {
                routeIterator.remove();
            }
        }

        System.out.println("Removing trips that are not on mode " + RETAIN_ROUTE_TYPE);
        Set<String> retainTrips = new HashSet<>();
        Iterator<Trip> tripIterator = feed.trips.values().iterator();
        while (tripIterator.hasNext()) {
            Trip trip = tripIterator.next();
            if (retainRoutes.contains(trip.route_id)) {
                retainTrips.add(trip.trip_id);
            } else {
                tripIterator.remove();
            }
        }

        System.out.println("Removing frequencies that are not on mode " + RETAIN_ROUTE_TYPE);
        Iterator<Fun.Tuple2<String, Frequency>> freqIterator = feed.frequencies.iterator();
        while (freqIterator.hasNext()) {
            Frequency frequency = freqIterator.next().b;
            if (!retainTrips.contains(frequency.trip_id)) {
                freqIterator.remove();
            }
        }

        System.out.println("Removing stop_times that are not on mode " + RETAIN_ROUTE_TYPE);
        Set<String> referencedStops = new HashSet<>();
        Iterator<StopTime> stIterator = feed.stop_times.values().iterator();
        while (stIterator.hasNext()) {
            StopTime stopTime = stIterator.next();
            if (retainTrips.contains(stopTime.trip_id)) {
                referencedStops.add(stopTime.stop_id);
            } else {
                stIterator.remove();
            }
        }

        System.out.println("Removing unreferenced stops...");
        Iterator<Stop> stopIterator = feed.stops.values().iterator();
        while (stopIterator.hasNext()) {
            Stop stop = stopIterator.next();
            if (!referencedStops.contains(stop.stop_id)) {
                stopIterator.remove();
            }
        }

        if (REMOVE_SHAPES) {
            System.out.println("Removing shapes table and removing shape IDs from trips...");
            feed.shape_points.clear();
            for (String tripId : feed.trips.keySet()) {
                Trip trip = feed.trips.get(tripId);
                trip.shape_id = null;
                // Entry.setValue is an unsupported operation in MapDB, just re-put the trip.
                feed.trips.put(tripId, trip);
            }
        }

        System.out.println("Filtering transfers for removed stops...");
        Iterator<Transfer> ti = feed.transfers.values().iterator();
        while (ti.hasNext()) {
            Transfer t = ti.next();
            if ( ! (referencedStops.contains(t.from_stop_id) && referencedStops.contains(t.to_stop_id))) {
                ti.remove();
            }
        }

        System.out.println("Writing GTFS...");
        feed.toFile(outputFile);
        feed.close();
    }

}
