package com.conveyal.gtfs;

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
 * Remove all stops outside the bounding box,
 * then remove all stop_times outside the bounding box,
 * recording all trips with two or more stop_times inside the bounding box.
 * Then remove all trips with no stoptimes or one single stoptime,
 * then remove all transfers whose stops have been removed.
 *
 * Note that this does not crop the GTFS shapes, only the stops and stoptimes.
 * Therefore in some tools like Transport Analyst, the data set will appear to extend beyond the bounding box
 * because the entire shapes are drawn.
 */
public class CropGTFS {

    // Logger is not super useful because as a library, gtfs-lib has no logger implementation defined by default.
    private static final Logger LOG = LoggerFactory.getLogger(CropGTFS.class);

    private static final String inputFile = "/Users/abyrd/test-est/gtfs_fr-cha_pourOAD.zip";
    private static final String outputFile = ""; //"/Users/abyrd/geodata/nl/NL-2016-08-23-noplatforms-noshapes.gtfs.zip";

    // Replace all stops with their parent stations to simplify trip patterns.
    private static final boolean MERGE_STATIONS = true;

    // Remove all shapes from the GTFS to make it simpler to render in a web UI
    private static final boolean REMOVE_SHAPES = true;

    public static void main (String[] args) {

        GTFSFeed feed = GTFSFeed.fromFile(inputFile);

        // We keep two sets of trip IDs because we only keep trips that are referenced by two or more stopTimes.
        // A TObjectIntMap would be good for this as well, but we don't currently depend on Trove.
        Set<String> referencedTripIds = new HashSet<>();
        Set<String> retainedTripIds = new HashSet<>();

        // The geometry within which we will keep all stops
        Geometry bounds = Geometries.getNetherlandsWithoutTexel();

        System.out.println("Removing stops outside bounding box...");
        Map<String, String> stopIdReplacements = new HashMap<>(); // Used when collapsing stops into stations.
        Iterator<Stop> stopIterator = feed.stops.values().iterator();
        while (stopIterator.hasNext()) {
            Stop stop = stopIterator.next();
            if (MERGE_STATIONS) {
                // Do not load stops that will be collapsed down into their parent stations.
                if (!Strings.isNullOrEmpty(stop.parent_station)) {
                    stopIdReplacements.put(stop.stop_id, stop.parent_station);
                    stopIterator.remove();
                    continue;
                }
                // Redefine stations as stops in case GTFS consuming software doesn't allow vehicles to stop at stations.
                // if (stop.location_type == 2) stop.location_type = 1;
            }
            if (!bounds.contains(Geometries.geometryFactory.createPoint(new Coordinate(stop.stop_lon, stop.stop_lat)))) {
                stopIterator.remove();
            }
        }

        if (MERGE_STATIONS) {
            System.out.println("Replacing stop_ids in stop_times with those of their parent stations...");
            for (Fun.Tuple2 key : feed.stop_times.keySet()) {
                StopTime stopTime = feed.stop_times.get(key);
                String replacementStopId = stopIdReplacements.get(stopTime.stop_id);
                if (replacementStopId != null) {
                    // Entry.setValue is an unsupported operation in MapDB, just re-put the StopTime.
                    stopTime.stop_id = replacementStopId;
                    feed.stop_times.put(key, stopTime);
                }
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

        System.out.println("Removing stop_times outside the bounding box and finding trips with two or more stop_times inside the bounding box...");
        Iterator<StopTime> stIterator = feed.stop_times.values().iterator();
        while (stIterator.hasNext()) {
            StopTime stopTime = stIterator.next();
            if (feed.stops.containsKey(stopTime.stop_id)) {
                // This stop has been retained because it's inside the bounding box.
                // Keep the stop_time, and also record the trip_id it belongs to so we can retain those.
                if (referencedTripIds.contains(stopTime.trip_id)) {
                    // This trip is referenced by two or more stopTimes within the bounding box.
                    retainedTripIds.add(stopTime.trip_id);
                } else {
                    // This is the first time this trip has been referenced by a stopTime within the bounding box.
                    referencedTripIds.add(stopTime.trip_id);
                }
            } else {
                // Skip stops outside the bounding box, but keep those within the bounding box.
                // It is important to remove these or we'll end up with trips referencing stop IDs that don't exist.
                stIterator.remove();
            }
        }

        System.out.println("Removing stoptimes for trips with less than two stop_times inside the bounding box...");
        // There are more efficient ways of doing this than iterating over all the stop times.
        // It could be done inside the trip removal loop below.
        stIterator = feed.stop_times.values().iterator();
        while (stIterator.hasNext()) {
            StopTime stopTime = stIterator.next();
            if ( ! retainedTripIds.contains(stopTime.trip_id)) {
                // This stop_time's trip is not referenced by two or more stopTimes within the bounding box.
                stIterator.remove();
            }
        }

        System.out.println("Removing trips that had less than two stop_times inside bounding box...");
        Iterator<Trip> tripIterator = feed.trips.values().iterator();
        while (tripIterator.hasNext()) {
            Trip trip = tripIterator.next();
            if ( ! retainedTripIds.contains(trip.trip_id)) {
                tripIterator.remove();
            }
        }

        if (MERGE_STATIONS) {
            System.out.println("Replacing stop_ids in transfers with those of their parent stations...");
            for (String key : feed.transfers.keySet()) {
                Transfer transfer = feed.transfers.get(key);
                String replacementStopId;
                replacementStopId = stopIdReplacements.get(transfer.from_stop_id);
                if (replacementStopId != null) transfer.from_stop_id = replacementStopId;
                replacementStopId = stopIdReplacements.get(transfer.to_stop_id);
                if (replacementStopId != null) transfer.to_stop_id = replacementStopId;
                feed.transfers.put(key, transfer);
            }
        }

        System.out.println("Filtering transfers for removed stops...");
        Iterator<Transfer> ti = feed.transfers.values().iterator();
        while (ti.hasNext()) {
            Transfer t = ti.next();
            if ( ! (feed.stops.containsKey(t.from_stop_id) && feed.stops.containsKey(t.to_stop_id))) {
                ti.remove();
            }
        }

        System.out.println("Writing GTFS...");
        feed.toFile(outputFile);
        feed.close();
    }

}
