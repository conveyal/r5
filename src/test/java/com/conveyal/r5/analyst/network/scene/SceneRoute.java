package com.conveyal.r5.analyst.network.scene;

import java.util.List;

/// Represents one scheduled transit route in a scene, running in one direction through a
/// sequence of stops. It is rendered as one GTFS route with timetabled trips departing the first
/// stop at a fixed headway between the first and last departure times. All trips are active on the
/// scene's single service date. Inter-stop hops all take the same time and dwell times are all zero.
public class SceneRoute {

    /// Used as the GTFS route_id and the prefix of its trip_ids.
    public final String id;

    List<SceneStop> stops = List.of();

    int hopSeconds = 60;

    int firstDeparture = 6 * 3600;

    int lastDeparture = 22 * 3600;

    int headwaySeconds = 600;

    SceneRoute (String id) {
        this.id = id;
    }

    /// The stops served, in order. At least two are required.
    public SceneRoute stops (SceneStop... stops) {
        this.stops = List.of(stops);
        return this;
    }

    /// The in-vehicle time between each pair of consecutive stops.
    public SceneRoute hopSeconds (int hopSeconds) {
        this.hopSeconds = hopSeconds;
        return this;
    }

    /// Trips depart the first stop at the given headway, from the first departure time through
    /// the last, in seconds after midnight.
    public SceneRoute departures (int firstSeconds, int lastSeconds, int headwaySeconds) {
        this.firstDeparture = firstSeconds;
        this.lastDeparture = lastSeconds;
        this.headwaySeconds = headwaySeconds;
        return this;
    }

}
