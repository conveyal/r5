package com.conveyal.r5.analyst.network.scene;

import com.conveyal.analysis.components.WorkerComponents;
import com.conveyal.file.FileStorageKey;
import com.conveyal.file.LocalFileStorage;
import com.conveyal.r5.analyst.FreeFormPointSet;
import com.conveyal.r5.analyst.TravelTimeComputer;
import com.conveyal.r5.analyst.WebMercatorExtents;
import com.conveyal.r5.OneOriginResult;
import com.conveyal.r5.analyst.cluster.RegionalTask;
import com.conveyal.r5.analyst.decay.StepDecayFunction;
import com.conveyal.r5.analyst.network.TestPointSetCache;
import com.conveyal.r5.analyst.scenario.AddOnDemand;
import com.conveyal.r5.analyst.scenario.Modification;
import com.conveyal.r5.analyst.scenario.Scenario;
import com.conveyal.r5.api.util.LegMode;
import com.conveyal.r5.api.util.TransitModes;
import com.conveyal.r5.transit.TransportNetwork;
import org.locationtech.jts.geom.Coordinate;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import static com.conveyal.file.FileCategory.DATASOURCES;
import static com.conveyal.r5.analyst.WebMercatorExtents.DEFAULT_ZOOM;
import static com.conveyal.r5.analyst.network.scene.SceneRouting.NOON;

/// Static helpers for applying scenario modifications to networks built from Scenes, and for
/// running full travel time computations on the results. Modifications read polygons from data
/// sources via the same code path as in production, but wired up to a local file storage directory
/// holding GeoJSON written from scene coordinates.
class SceneModifications {

    /// Set the worker to use local file storage configured to read/write the given directory.
    /// Data sources written there with [#writePolygons] are then found by modifications
    /// through the production loading path.
    static void useFileStorage (Path directory) {
        WorkerComponents.fileStorage = new LocalFileStorage(new LocalFileStorage.Config() {
            @Override
            public String localCacheDirectory () {
                return directory.toString();
            }
            @Override
            public int serverPort () {
                return 0;
            }
        });
    }

    /// A single rectangle as a Polygon, or several as one MultiPolygon (one GeoJSON feature).
    /// Rectangles are given in scene coordinates as (minX, minY, maxX, maxY) in meters.
    record Area (String id, int[]... rectangles) { }

    /// Write the given areas as a GeoJSON data source with the given name into file storage,
    /// returning the name for use as a modification's polygon data source.
    static String writePolygons (Scene scene, String name, Area... areas) {
        List<String> features = new ArrayList<>();
        for (Area area : areas) {
            List<String> polygons = new ArrayList<>();
            for (int[] r : area.rectangles) {
                polygons.add("[" + ring(scene, r) + "]");
            }
            String geometry = area.rectangles.length == 1
                ? "{\"type\":\"Polygon\",\"coordinates\":" + polygons.get(0) + "}"
                : "{\"type\":\"MultiPolygon\",\"coordinates\":[" + String.join(",", polygons) + "]}";
            features.add(String.format(
                "{\"type\":\"Feature\",\"properties\":{\"id\":\"%s\",\"name\":\"%s\"},\"geometry\":%s}",
                area.id, area.id, geometry));
        }
        String json = "{\"type\":\"FeatureCollection\",\"features\":[" + String.join(",", features) + "]}";
        File file = WorkerComponents.fileStorage.getFile(new FileStorageKey(DATASOURCES, name));
        file.getParentFile().mkdirs();
        try {
            Files.writeString(file.toPath(), json, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return name;
    }

    /// Given a rectangle in scene coordinates in meters, produce a String containing a GeoJSON ring.
    private static String ring (Scene scene, int[] r) {
        int[][] corners = {{r[0], r[1]}, {r[2], r[1]}, {r[2], r[3]}, {r[0], r[3]}, {r[0], r[1]}};
        List<String> positions = new ArrayList<>();
        for (int[] c : corners) {
            positions.add(String.format("[%.7f,%.7f]", scene.lonForXY(c[0], c[1]), scene.latForY(c[1])));
        }
        return "[" + String.join(",", positions) + "]";
    }

    /// A service stating neither wait nor factor, inheriting the modification-level values.
    static AddOnDemand.Service service (String from, String to) {
        AddOnDemand.Service service = new AddOnDemand.Service();
        service.from = from;
        service.to = to;
        return service;
    }

    static AddOnDemand.Service service (String from, String to, double waitMinutes) {
        AddOnDemand.Service service = new AddOnDemand.Service();
        service.from = from;
        service.to = to;
        service.waitMinutes = waitMinutes;
        return service;
    }

    static AddOnDemand addOnDemand (String polygons, AddOnDemand.Service... services) {
        AddOnDemand modification = new AddOnDemand();
        modification.polygons = polygons;
        modification.services = List.of(services);
        return modification;
    }

    /// Apply the given modifications to the base network as one scenario, through the same
    /// scenario application process used in production.
    static TransportNetwork apply (TransportNetwork base, String scenarioId, Modification... modifications) {
        Scenario scenario = new Scenario();
        scenario.id = scenarioId;
        scenario.modifications.addAll(List.of(modifications));
        return scenario.applyToTransportNetwork(base);
    }

    /// Return travel times in whole minutes from the given origin to the given destination points
    /// (scene coordinate pairs) using the full TravelTimeComputer with the given access modes and
    /// no scheduled transit. Unreached points have the value Integer.MAX_VALUE.
    static int[] minutesTo (
          TransportNetwork network, Scene scene, double x, double y, EnumSet<LegMode> accessModes, double[]... xy) {
        return minutesTo(network, scene, x, y, accessModes, EnumSet.of(LegMode.WALK), xy);
    }

    static int[] minutesTo (
          TransportNetwork network, Scene scene, double x, double y,
          EnumSet<LegMode> accessModes, EnumSet<LegMode> egressModes, double[]... xy) {
        return minutes(network, scene, x, y, accessModes, egressModes, false, xy);
    }

    /// Like [#minutesTo] but also using any scheduled transit in the network, with walk egress.
    /// Departures are spread over a one-hour window, so travel times vary. We return the median.
    static int[] minutesByTransit (
          TransportNetwork network, Scene scene, double x, double y, EnumSet<LegMode> accessModes, double[]... xy) {
        return minutes(network, scene, x, y, accessModes, EnumSet.of(LegMode.WALK), true, xy);
    }

    /// The common core of minutesTo and minutesByTransit above, which set the egress modes and
    /// transit usage. Builds a RegionalTask with free-form destinations at the given scene
    /// coordinate pairs and default speeds and limits, runs it through TravelTimeComputer, and
    /// returns the median travel time in whole minutes to each destination in the order given, with
    /// Integer.MAX_VALUE meaning unreached.
    private static int[] minutes (
          TransportNetwork network, Scene scene, double x, double y,
          EnumSet<LegMode> accessModes, EnumSet<LegMode> egressModes, boolean transit, double[]... xy) {
        RegionalTask task = new RegionalTask();
        task.date = Scene.SERVICE_DATE;
        task.fromTime = NOON - 1800;
        task.toTime = NOON + 1800;
        task.fromLat = scene.latForY(y);
        task.fromLon = scene.lonForXY(x, y);
        task.accessModes = accessModes;
        task.directModes = accessModes;
        task.egressModes = egressModes;
        task.transitModes = transit ? EnumSet.allOf(TransitModes.class) : EnumSet.noneOf(TransitModes.class);

        task.percentiles = new int[] {50};
        task.cutoffsMinutes = new int[] {60};
        task.decayFunction = new StepDecayFunction();
        task.monteCarloDraws = 10;
        task.recordTimes = true;
        WebMercatorExtents extents = WebMercatorExtents.forWgsEnvelope(network.streetLayer.envelope, DEFAULT_ZOOM);
        task.zoom = extents.zoom;
        task.north = extents.north;
        task.west = extents.west;
        task.width = extents.width;
        task.height = extents.height;
        Coordinate[] coordinates = new Coordinate[xy.length];
        for (int i = 0; i < xy.length; i++) {
            coordinates[i] = new Coordinate(scene.lonForXY(xy[i][0], xy[i][1]), scene.latForY(xy[i][1]));
        }
        TestPointSetCache cache = new TestPointSetCache();
        cache.put("POINTS", new FreeFormPointSet(coordinates));
        task.destinationPointSetKeys = new String[] {"POINTS"};
        task.loadAndValidateDestinationPointSets(cache);
        OneOriginResult result = new TravelTimeComputer(task, network).computeTravelTimes();
        return result.travelTimes.getValues()[0];
    }

}
