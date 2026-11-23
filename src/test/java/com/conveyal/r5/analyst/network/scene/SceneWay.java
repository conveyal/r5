package com.conveyal.r5.analyst.network.scene;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/// Used to build one OSM way as a sequence of points with coordinates in meters east and north of
/// the scene origin. A sequence is begun with a call to [#from], supplying either a junction or
/// bare coordinates if the way begins at a dead end. Other fluent methods like [#east], [#north],
/// and [#step] make directional moves and extend the sequence with non-intersection geometry points.
///
/// Passing a [SceneJunction] to [#via] or [#to] appends the junction's coordinates and attaches
/// the way to that junction such that they are topologically connected by an intersection.
///
/// All the fluent methods return this way instance to allow method chaining.
/// A way must have at least two points when the scene is built.
public class SceneWay {

    final Scene scene;

    public final WayPreset preset;

    String name;

    boolean oneWay = false;

    /// All OSM tags for this way, initialized from the preset's tags. See [#tag].
    final Map<String, String> tags = new LinkedHashMap<>();

    final List<Point> points = new ArrayList<>();

    /// One point along the linestring-like geometry of the way, holding both coordinates in meters
    /// and the junction it is associated with, if any. The coordinates are doubles rather than integers
    /// because [Scene#join] may split a diagonal segment at a non-integer position. Points that are
    /// declared rather than derived from a join operation always have integer coordinates.
    static final class Point {
        final double x, y;
        SceneJunction junction;

        Point (double x, double y, SceneJunction junction) {
            this.x = x;
            this.y = y;
            this.junction = junction;
        }
    }

    SceneWay (Scene scene, WayPreset preset) {
        this.scene = scene;
        this.preset = preset;
        tags.putAll(preset.tags);
    }

    /// Start the sequence of points at a junction, associating the start point with that junction entity.
    public SceneWay from (SceneJunction junction) {
        checkEmpty();
        points.add(new Point(junction.x, junction.y, junction));
        return this;
    }

    /// Start the sequence of points at non-junction coordinates, typically a dead end.
    public SceneWay from (int x, int y) {
        checkEmpty();
        points.add(new Point(x, y, null));
        return this;
    }

    /// Append a point displaced from the previous one by the given distances in meters.
    /// The new point is not associated with any junction.
    public SceneWay step (int dx, int dy) {
        checkStarted();
        Point last = points.getLast();
        points.add(new Point(last.x + dx, last.y + dy, null));
        return this;
    }

    public SceneWay east (int meters) { return step(meters, 0); }

    public SceneWay west (int meters) { return step(-meters, 0); }

    public SceneWay north (int meters) { return step(0, meters); }

    public SceneWay south (int meters) { return step(0, -meters); }

    /// Continue the sequence of points to a junction's coordinates,
    /// associating the way with the junction at that location.
    public SceneWay via (SceneJunction junction) {
        checkStarted();
        points.add(new Point(junction.x, junction.y, junction));
        return this;
    }

    /// End the sequence of points at a junction.
    /// Identical to [#via], with the distinct name only marking intent.
    public SceneWay to (SceneJunction junction) {
        return via(junction);
    }

    /// Add one OSM tag to this way, beyond the tags supplied by the preset. Existing tags may not
    /// be overwritten, whether they came from the preset, an earlier call to this method, or the
    /// other fluent methods that set tags.
    public SceneWay tag (String key, String value) {
        String existing = tags.get(key);
        if (existing != null) {
            throw new IllegalArgumentException(String.format(
                "Way '%s' already has tag %s=%s.", describe(), key, existing));
        }
        tags.put(key, value);
        return this;
    }

    /// Make this way one-way in the direction of the sequence of points (adds the tag oneway=yes).
    public SceneWay oneWay () {
        this.oneWay = true;
        return tag("oneway", "yes");
    }

    /// Set the OSM name tag, also used to label the way in SVG output.
    public SceneWay named (String name) {
        this.name = name;
        return tag("name", name);
    }

    /// Create an independent copy of this way in the same scene, displaced by the given distances
    /// in meters. The copy has the same coordinates, tags and preset, but is not associated with
    /// the same junctions. It is initially disconnected from every other way. The only means of
    /// connecting it is through [Scene#join], which declares a new junction and attaches it to the
    /// copy. After that, further ways can also connect to the copy at that junction.
    public SceneWay translated (int dx, int dy) {
        SceneWay copy = scene.way(preset);
        copy.name = name;
        copy.oneWay = oneWay;
        copy.tags.putAll(tags);
        for (Point p : points) {
            copy.points.add(new Point(p.x + dx, p.y + dy, null));
        }
        return copy;
    }

    /// Bind this way to the given junction. If the way contains an existing point lying exactly
    /// at the junction's coordinates, that point is bound directly to the junction. Failing that,
    /// if the junction lies exactly along a segment, the segment is split and a new point bound to
    /// the junction is inserted. Returns false if the junction does not lie on this way.
    /// Note this is an internal method and not part of the public API.
    boolean bindAt (SceneJunction junction) {
        for (Point p : points) {
            if (p.x == junction.x && p.y == junction.y) {
                if (p.junction != null && p.junction != junction) {
                    throw new IllegalArgumentException(String.format(
                        "Cannot join at %s: way '%s' already has %s at those coordinates.",
                        junction, describe(), p.junction));
                }
                p.junction = junction;
                return true;
            }
        }
        for (int i = 0; i < points.size() - 1; i++) {
            if (segmentInteriorContains(points.get(i), points.get(i + 1), junction.x, junction.y)) {
                points.add(i + 1, new Point(junction.x, junction.y, junction));
                return true;
            }
        }
        return false;
    }

    /// @return true if (x, y) lies on the segment between points a and b, excluding the endpoints
    ///  themselves, which the caller handles as exact vertex matches.
    private static boolean segmentInteriorContains (Point a, Point b, double x, double y) {
        final double EPS = 1e-6;
        double abx = b.x - a.x, aby = b.y - a.y;
        double apx = x - a.x, apy = y - a.y;
        double cross = abx * apy - aby * apx;
        double lengthSquared = abx * abx + aby * aby;
        if (Math.abs(cross) > EPS * Math.sqrt(lengthSquared)) return false;
        double dot = abx * apx + aby * apy;
        return dot > EPS && dot < lengthSquared - EPS;
    }

    String describe () {
        return name != null ? name : preset.name().toLowerCase() + "@" + startPoint();
    }

    private String startPoint () {
        if (points.isEmpty()) return "(unstarted)";
        Point p = points.getFirst();
        return String.format("(%.0f, %.0f)", p.x, p.y);
    }

    private void checkEmpty () {
        if (!points.isEmpty()) {
            throw new IllegalStateException("from() must be the first call when building a way.");
        }
    }

    private void checkStarted () {
        if (points.isEmpty()) {
            throw new IllegalStateException("Start the way with from() before adding more points.");
        }
    }

    @Override
    public String toString () {
        return String.format("SceneWay %s (%s, %d points)", describe(), preset, points.size());
    }

}
