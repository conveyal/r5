package com.conveyal.r5.analyst.network.scene;

import com.conveyal.gtfs.GTFSFeed;
import com.conveyal.gtfs.flex.FlexGroup;
import com.conveyal.gtfs.flex.FlexLocation;
import com.conveyal.gtfs.flex.FlexStopTime;
import com.conveyal.gtfs.flex.FlexTrip;
import com.conveyal.gtfs.geom.CPolygon;
import com.conveyal.gtfs.model.Agency;
import com.conveyal.gtfs.model.CalendarDate;
import com.conveyal.gtfs.model.Route;
import com.conveyal.gtfs.model.Service;
import com.conveyal.gtfs.model.Stop;
import org.mapdb.Fun;

import java.io.File;
import java.util.ArrayList;

/// Renders the transit elements of a Scene into an in-memory or file-backed GTFS MapDB in one pass.
/// GTFS entities never pass through the CSV and ZIP layers, and are instead rendered directly to
/// rows in MapDB tables. The tables present will depend on what scene primitives are present.
/// For example, flex trips and locations will only be present when a SceneOnDemand is added.
class GtfsRenderer {

    static final String AGENCY_ID = "SCENE";
    static final String SERVICE_ID = "ALL";
    static final String FLEX_ROUTE_ID = "FLEX";

    /// Render the scene to a GTFSFeed backed by the given file, or in memory if the file parameter is null.
    /// Note that sidecar `.p` files may be produced in addition to the base dbFile you specify.
    static GTFSFeed render (Scene scene, File dbFile) {
        GTFSFeed feed = dbFile == null ? GTFSFeed.newWritableInMemory() : GTFSFeed.newWritableFile(dbFile);

        Agency agency = new Agency();
        agency.agency_id = AGENCY_ID;
        agency.agency_name = AGENCY_ID;
        feed.agency.put(agency.agency_id, agency);

        // A single service running only on Scene.SERVICE_DATE.
        Service service = new Service(SERVICE_ID);
        CalendarDate calendarDate = new CalendarDate();
        calendarDate.date = Scene.SERVICE_DATE;
        calendarDate.service_id = SERVICE_ID;
        calendarDate.exception_type = 1;
        service.calendar_dates.put(calendarDate.date, calendarDate);
        feed.services.put(service.service_id, service);

        for (SceneStop sceneStop : scene.stops) {
            Stop stop = new Stop();
            stop.stop_id = sceneStop.id;
            stop.stop_name = sceneStop.id;
            stop.stop_lat = scene.latForY(sceneStop.y);
            stop.stop_lon = scene.lonForXY(sceneStop.x, sceneStop.y);
            feed.stops.put(stop.stop_id, stop);
        }

        for (ScenePolygon polygon : scene.polygons.values()) {
            feed.locations.put(polygon.id, new FlexLocation(polygon.id, polygon.id, null, toCPolygon(scene, polygon)));
        }

        if (!scene.onDemands.isEmpty()) {
            Route route = new Route();
            route.route_id = FLEX_ROUTE_ID;
            route.agency_id = AGENCY_ID;
            route.route_short_name = FLEX_ROUTE_ID;
            route.route_type = 3;
            feed.routes.put(route.route_id, route);
            for (SceneOnDemand od : scene.onDemands) {
                FlexTrip trip = new FlexTrip();
                trip.trip_id = od.id;
                trip.route_id = FLEX_ROUTE_ID;
                trip.service_id = SERVICE_ID;
                trip.safe_duration_factor = od.durationFactor;
                trip.safe_duration_offset = od.durationOffset;
                feed.trips.put(trip.trip_id, trip);
                feed.flexTripIds.add(trip.trip_id);
                addFlexStopTime(feed, od, od.from, 0);
                addFlexStopTime(feed, od, od.to, 1);
            }
        }

        feed.findPatterns();
        return feed;
    }

    /// Add the pickup (sequence 0) or drop-off (sequence 1) stop_time of a flex trip, creating the
    /// relevant location group when the endpoint is a collection of stops as opposed to a polygon.
    private static void addFlexStopTime (GTFSFeed feed, SceneOnDemand od, SceneOnDemand.Endpoint endpoint, int sequence) {
        FlexStopTime stopTime = new FlexStopTime();
        stopTime.trip_id = od.id;
        stopTime.stop_sequence = sequence;
        stopTime.start_pickup_drop_off_window = endpoint.windowStart;
        stopTime.end_pickup_drop_off_window = endpoint.windowEnd;
        if (endpoint.polygon != null) {
            stopTime.location_id = endpoint.polygon.id;
        } else {
            FlexGroup group = new FlexGroup();
            group.location_group_id = od.id + (sequence == 0 ? ":from" : ":to");
            group.location_group_name = group.location_group_id;
            group.stop_ids = new ArrayList<>(endpoint.stops.stream().map(s -> s.id).toList());
            feed.location_groups.put(group.location_group_id, group);
            stopTime.location_group_id = group.location_group_id;
        }
        feed.stop_times.put(new Fun.Tuple2<>(od.id, stopTime.stop_sequence), stopTime);
    }

    /// Convert the single ring of a ScenePolygon (in local meters) to a CPolygon in WGS84.
    /// Conversion of vertices is independent and deterministic, so the ring remains closed in WGS84.
    private static CPolygon toCPolygon (Scene scene, ScenePolygon polygon) {
        double[] packedLonLat = new double[polygon.ringXY.length];
        for (int i = 0; i < polygon.ringXY.length; i += 2) {
            double x = polygon.ringXY[i];
            double y = polygon.ringXY[i + 1];
            packedLonLat[i] = scene.lonForXY(x, y);
            packedLonLat[i + 1] = scene.latForY(y);
        }
        return new CPolygon(packedLonLat);
    }

}
