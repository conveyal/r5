package com.conveyal.r5.analyst.network.scene;

import com.conveyal.gtfs.GTFSFeed;
import com.conveyal.osmlib.OSM;
import com.conveyal.r5.analyst.cluster.TransportNetworkConfig;
import com.conveyal.r5.common.SphericalDistanceLibrary;
import com.conveyal.r5.streets.StreetLayer;
import com.conveyal.r5.transit.TransportNetwork;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.CoordinateXY;
import org.locationtech.jts.algorithm.RobustLineIntersector;
import org.locationtech.jts.geom.LineSegment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/// Scene instances are used for constructing and validating small test networks.
/// See the package docs for more detail.
/// All coordinates are integer meters east (x) and north (y) of the scene origin.
public class Scene {

    private static final Logger LOG = LoggerFactory.getLogger(Scene.class);

    /// Same corner of the Simpson Desert used by the GridLayout tests: flat, water-free, far from
    /// any real transport network, and at a latitude where lon/lat degree lengths clearly differ.
    public static final Coordinate DEFAULT_ORIGIN = new CoordinateXY(136.5, -25.5);

    /// The single date on which all scene GTFS services run.
    /// Requests routed on scene networks must use this date.
    public static final LocalDate SERVICE_DATE = LocalDate.of(2020, 1, 1);

    /// Temporary files will be created at these filenames in a known directory to facilitate cleanup.
    public static final String OSM_DB_NAME = "scene.osm.db";
    public static final String GTFS_DB_NAME = "scene.gtfs.db";

    /// This scene's origin point (southwest corner) in WGS84.
    /// It has no effect internally and should only affect rendering out to OSM and GTFS.
    public final Coordinate origin;

    final List<SceneWay> ways = new ArrayList<>();

    final Map<String, SceneJunction> junctions = new LinkedHashMap<>();

    final List<SceneStop> stops = new ArrayList<>();

    final Map<String, ScenePolygon> polygons = new LinkedHashMap<>();

    final List<SceneOnDemand> onDemands = new ArrayList<>();

    public Scene () {
        this(DEFAULT_ORIGIN);
    }

    public Scene (Coordinate origin) {
        this.origin = origin;
    }

    // -- Scene construction methods --

    /// Declare a named junction at the given meter coordinates.
    /// These are added to ways to create intersections with other ways.
    public SceneJunction junction (String name, int x, int y) {
        if (junctions.containsKey(name)) {
            throw new IllegalArgumentException("Duplicate junction name: " + name);
        }
        SceneJunction junction = new SceneJunction(name, x, y);
        junctions.put(name, junction);
        return junction;
    }

    /// Begins constructing a new way with the specified `highway=` tags.
    /// Nodes and further details are added with the fluent [SceneWay] methods.
    public SceneWay way (WayPreset preset) {
        SceneWay way = new SceneWay(this, preset);
        ways.add(way);
        return way;
    }

    /// Declare a transit stop at the given coordinates in meters east and north.
    public SceneStop stop (String id, int x, int y) {
        if (stops.stream().anyMatch(s -> s.id.equals(id))) {
            throw new IllegalArgumentException("Duplicate stop id: " + id);
        }
        SceneStop stop = new SceneStop(id, x, y);
        stops.add(stop);
        return stop;
    }

    /// Declare an on-demand pick-up/drop-off polygon from a packed array of coordinates in meters east and north.
    public ScenePolygon polygon (String id, int... unclosedXY) {
        if (polygons.containsKey(id)) {
            throw new IllegalArgumentException("Duplicate polygon id: " + id);
        }
        ScenePolygon polygon = new ScenePolygon(id, unclosedXY);
        polygons.put(id, polygon);
        return polygon;
    }

    /// Declare a rectangular on-demand pickup/drop-off polygon by its southwest and northeast corners.
    public ScenePolygon rectPolygon (String id, int minX, int minY, int maxX, int maxY) {
        return polygon(id, minX, minY, maxX, minY, maxX, maxY, minX, maxY);
    }

    /// Declare an on-demand trip with the given ID.
    /// Its pick-up and drop-off locations or stops are added with fluent [SceneOnDemand] methods.
    public SceneOnDemand onDemand (String id) {
        if (onDemands.stream().anyMatch(t -> t.id.equals(id))) {
            throw new IllegalArgumentException("Duplicate on-demand trip id: " + id);
        }
        SceneOnDemand spec = new SceneOnDemand(id);
        onDemands.add(spec);
        return spec;
    }

    /// Connect two already-built ways together at the given point, creating a new junction there
    /// and splitting the relevant segment of each way as needed. The given point must lie exactly
    /// on both ways, either as a vertex or along a line segment, otherwise an exception is thrown.
    /// This can be used to connect copies of ways that have been shifted (translated) in space.
    public SceneJunction join (SceneWay a, SceneWay b, String junctionName, int x, int y) {
        SceneJunction junction = junction(junctionName, x, y);
        if (!a.bindAt(junction)) {
            throw new IllegalArgumentException(String.format(
                "join point (%d, %d) does not lie on way '%s'", x, y, a.describe()));
        }
        if (!b.bindAt(junction)) {
            throw new IllegalArgumentException(String.format(
                "join point (%d, %d) does not lie on way '%s'", x, y, b.describe()));
        }
        return junction;
    }

    // -- Coordinate frame methods --

    /// @return the WGS84 latitude of a point y meters north of the scene origin.
    public double latForY (double yMeters) {
        return origin.y + SphericalDistanceLibrary.metersToDegreesLatitude(yMeters);
    }

    /// @return the WGS84 longitude of a point x meters east and y meters north of the scene origin.
    ///  Longitude for a given x in meters is dependent on latitude, so both coordinates are needed.
    public double lonForXY (double xMeters, double yMeters) {
        return origin.x + SphericalDistanceLibrary.metersToDegreesLongitude(xMeters, latForY(yMeters));
    }

    // -- Validation methods --

    /// Check the scene for construction errors and throw an IllegalStateException if any are found.
    /// Undeclared way crossings are legitimate where there are overpasses, but are logged as
    /// warnings in case they are forgotten junctions.
    public void validate () {
        List<String> errors = new ArrayList<>();
        for (SceneWay way : ways) {
            if (way.points.size() < 2) {
                errors.add(String.format("SceneWay '%s' has fewer than two points.", way.describe()));
            }
        }
        Map<SceneJunction, Set<SceneWay>> waysBoundToJunction = new LinkedHashMap<>();
        for (SceneJunction junction : junctions.values()) {
            waysBoundToJunction.put(junction, new java.util.LinkedHashSet<>());
        }
        for (SceneWay way : ways) {
            for (SceneWay.Point point : way.points) {
                if (point.junction != null) {
                    waysBoundToJunction.get(point.junction).add(way);
                }
            }
        }
        waysBoundToJunction.forEach((junction, boundWays) -> {
            if (boundWays.size() < 2) {
                errors.add(String.format(
                    "Unused junction: %s is attached to %d way(s) but junctions must connect at least two.",
                    junction, boundWays.size()));
            }
        });
        for (SceneOnDemand spec : onDemands) {
            if (!spec.from.isDefined() || !spec.to.isDefined()) {
                errors.add(String.format(
                    "On-demand trip '%s' must have exactly one polygon or stop group on each end.", spec.id));
            }
        }
        if (!errors.isEmpty()) {
            throw new IllegalStateException("Scene is invalid:\n  " + String.join("\n  ", errors));
        }
        for (String crossing : findUndeclaredCrossings()) {
            LOG.warn("Undeclared way crossing interpreted as an overpass: {}", crossing);
        }
    }

    /// Splits all SceneWays into their constituent line segments, intersects every segment with
    /// every other segment using JTS, and reports each intersection point that does not coincide
    /// with a junction declared on both ways. Such non-connected ways may be legitimate overpasses,
    /// but may also be forgotten junctions.
    public List<String> findUndeclaredCrossings () {
        record Seg (SceneWay way, int index, SceneWay.Point a, SceneWay.Point b, LineSegment line) { }
        List<Seg> segments = new ArrayList<>();
        for (SceneWay way : ways) {
            for (int i = 0; i < way.points.size() - 1; i++) {
                SceneWay.Point a = way.points.get(i);
                SceneWay.Point b = way.points.get(i + 1);
                segments.add(new Seg(way, i, a, b, new LineSegment(a.x, a.y, b.x, b.y)));
            }
        }
        final double EPS = 1e-6;
        List<String> crossings = new ArrayList<>();
        // One intersector reused for all pairs. computeIntersection resets its state on each call,
        // and its first step is an allocation-free envelope check rejecting most distant pairs.
        RobustLineIntersector intersector = new RobustLineIntersector();
        for (int i = 0; i < segments.size(); i++) {
            for (int j = i + 1; j < segments.size(); j++) {
                Seg s1 = segments.get(i);
                Seg s2 = segments.get(j);
                // Adjacent segments in a way always share a vertex, so skip them.
                if (s1.way == s2.way && Math.abs(s1.index - s2.index) <= 1) continue;
                intersector.computeIntersection(s1.line.p0, s1.line.p1, s2.line.p0, s2.line.p1);
                if (!intersector.hasIntersection()) continue;
                Coordinate ix = intersector.getIntersection(0);
                // Crossing has been declared if the intersection point coincides with the same
                // declared junction at an endpoint of both segments.
                boolean declared = false;
                for (SceneWay.Point p : new SceneWay.Point[] {s1.a, s1.b}) {
                    if (p.junction == null) continue;
                    if (Math.abs(p.x - ix.x) > EPS || Math.abs(p.y - ix.y) > EPS) continue;
                    for (SceneWay.Point q : new SceneWay.Point[] {s2.a, s2.b}) {
                        if (q.junction == p.junction) declared = true;
                    }
                }
                if (!declared) {
                    crossings.add(String.format("ways '%s' and '%s' at (%.1f, %.1f)",
                        s1.way.describe(), s2.way.describe(), ix.x, ix.y));
                }
            }
        }
        return crossings;
    }

    // -- Rendering and network building --

    /// Identical to [#buildNetwork(Path)] but with both intermediate MapDBs (OSM and GTFS) in
    /// heap memory, so the build creates no files.
    public TransportNetwork buildNetwork () {
        return buildNetwork(null);
    }

    /// Build a TransportNetwork from this Scene using the standard production methods.
    /// The intermediate OSM and GTFS MapDBs are backed by files with known names in the specified
    /// directory, allowing for easy cleanup (deletion).
    /// The only non-default option we set on the build is disabling island pruning, which would
    /// treat entire small test fixtures as islands.
    public TransportNetwork buildNetwork (Path storageDir) {
        validate();
        OSM osm = OsmRenderer.render(this, osmStorageFile(storageDir));
        GTFSFeed gtfs = GtfsRenderer.render(this, gtfsStorageFile(storageDir));
        TransportNetworkConfig config = new TransportNetworkConfig();
        config.pruneIslands = false;
        return TransportNetwork.build(config, osm, Stream.of(gtfs), true);
    }

    /// Identical to [#buildStreetLayer(Path)] but with the OSM MapDB in heap memory, so the
    /// build creates no files.
    public StreetLayer buildStreetLayer () {
        return buildStreetLayer(null);
    }

    /// Build only the street layer of this Scene for tests that examine streets alone. The street
    /// spatial index and edge lists are built. Stops, polygons and on-demand trips in this Scene
    /// are ignored, and tests that involve stop linking must use the full [#buildNetwork] method.
    /// The OSM MapDB file is placed in the given directory. Island pruning is disabled to protect
    /// small scenes as in buildNetwork.
    public StreetLayer buildStreetLayer (Path storageDir) {
        validate();
        OSM osm = OsmRenderer.render(this, osmStorageFile(storageDir));
        StreetLayer streetLayer = new StreetLayer((TransportNetworkConfig) null);
        streetLayer.loadFromOsm(osm, false, false);
        osm.close();
        streetLayer.indexStreets();
        streetLayer.buildEdgeLists();
        return streetLayer;
    }

    /// Render this Scene as a simple SVG diagram to help visualize and debug the network layout.
    /// Deliberately skips validation to allow debugging broken scenes.
    public String toSvg () {
        return SvgRenderer.render(this);
    }

    /// Render this Scene as a simple SVG diagram, saving the diagram to the given file.
    public void writeSvg (Path file) {
        try {
            Files.writeString(file, toSvg(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /// @return the standard OSM MapDB storage file in the specified directory,
    ///   or null if the directory is null meaning in-memory storage.
    private static File osmStorageFile (Path storageDir) {
        if (storageDir == null) return null;
        Path path = storageDir.resolve(OSM_DB_NAME);
        checkAbsent(path);
        return path.toFile();
    }

    /// @return the standard GTFS MapDB storage file in the specified directory,
    ///   or null if the directory is null meaning in-memory storage.
    private static File gtfsStorageFile (Path storageDir) {
        if (storageDir == null) return null;
        Path path = storageDir.resolve(GTFS_DB_NAME);
        checkAbsent(path);
        return path.toFile();
    }

    private static void checkAbsent (Path path) {
        if (Files.exists(path)) {
            throw new IllegalStateException(
                "Scene DB file already present, supply a fresh empty directory per build: " + path);
        }
    }

}
