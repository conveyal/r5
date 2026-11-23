package com.conveyal.r5.analyst.network.scene;

import java.util.List;

import static com.conveyal.gtfs.model.Entity.INT_MISSING;

/// Represents one on-demand trip in the scene. The trip is rendered as one FlexTrip with two
/// FlexStopTimes, which is the only type of flex trip R5 supports, and becomes a single OnDemand
/// instance in the built network.
///
/// The pick-up endpoint and drop-off endpoint can be either a [ScenePolygon] or a group of
/// [SceneStop]s which will be rendered as a GTFS Flex location group. Each can have a time window.
///
/// Windows are in seconds after midnight. An endpoint declared without a window is rendered with
/// the window fields missing. The network build then logs a warning and treats the service as
/// always available, matching production behavior.
public class SceneOnDemand {

    /// Used as the GTFS trip_id.
    public final String id;

    final Endpoint from = new Endpoint();

    final Endpoint to = new Endpoint();

    double durationFactor = 1.0;

    double durationOffset = 0.0;

    /// One endpoint of an on-demand trip, on either the pickup or the drop-off side.
    /// Exactly one of polygon or stops must be set.
    static final class Endpoint {
        ScenePolygon polygon;
        List<SceneStop> stops;
        int windowStart = INT_MISSING;
        int windowEnd = INT_MISSING;

        boolean isDefined () {
            return (polygon != null) ^ (stops != null);
        }
    }

    SceneOnDemand (String id) {
        this.id = id;
    }

    /// Pick up anywhere in the given polygon.
    public SceneOnDemand fromPolygon (ScenePolygon polygon) {
        from.polygon = polygon;
        return this;
    }

    /// Pick up at the given stops, which are rendered as a Flex location group.
    public SceneOnDemand fromStops (SceneStop... stops) {
        from.stops = List.of(stops);
        return this;
    }

    /// Drop off anywhere in the given polygon.
    public SceneOnDemand toPolygon (ScenePolygon polygon) {
        to.polygon = polygon;
        return this;
    }

    /// Drop off at the given stops, which are rendered as a Flex location group.
    public SceneOnDemand toStops (SceneStop... stops) {
        to.stops = List.of(stops);
        return this;
    }

    /// Set the pick-up time window in seconds after midnight, rendered on the pick-up stop_time.
    public SceneOnDemand pickupWindow (int startSeconds, int endSeconds) {
        from.windowStart = startSeconds;
        from.windowEnd = endSeconds;
        return this;
    }

    /// Set the drop-off time window in seconds after midnight, rendered on the drop-off stop_time.
    public SceneOnDemand dropOffWindow (int startSeconds, int endSeconds) {
        to.windowStart = startSeconds;
        to.windowEnd = endSeconds;
        return this;
    }

    /// Set the GTFS Flex safe_duration_factor. Ride duration is scaled by this factor.
    public SceneOnDemand durationFactor (double factor) {
        this.durationFactor = factor;
        return this;
    }

    /// Set the GTFS Flex safe_duration_offset in seconds.
    /// This much waiting time is added before the ride begins.
    public SceneOnDemand durationOffset (double seconds) {
        this.durationOffset = seconds;
        return this;
    }

    @Override
    public String toString () {
        return "SceneOnDemand " + id;
    }

}
