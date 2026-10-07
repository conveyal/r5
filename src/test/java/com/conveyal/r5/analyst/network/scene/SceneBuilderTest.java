package com.conveyal.r5.analyst.network.scene;

import com.conveyal.gtfs.flex.OnDemand;
import com.conveyal.r5.common.SphericalDistanceLibrary;
import com.conveyal.r5.transit.TransportNetwork;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.CoordinateXY;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Builds an example Scene end to end and checks the scene builder outputs that no other test
/// covers: the coordinate frame, the file-backed build path (OSM and GTFS MapDBs in a per-test
/// temporary directory that JUnit deletes), on-demand service fields passing through the GTFS
/// renderer into the built network, and the SVG diagram output. Routing behavior on scene networks
/// is covered by [StopLinkingTest] and [OnDemandStopAccessTest], whose networks are built in heap
/// memory without files. The scene is illustrated in exampleScene.svg.
public class SceneBuilderTest {

    @TempDir
    private Path tempDir;

    /// The example scene: a main street with a cross street, a footpath to a stop, and a flex
    /// service from a pickup polygon to that stop.
    static Scene exampleScene () {
        Scene scene = new Scene();
        SceneJunction cross = scene.junction("cross", 400, 0);
        SceneJunction path = scene.junction("path", 600, 0);
        scene.way(WayPreset.STREET).named("Main St").from(0, 0).via(cross).via(path).east(200);
        scene.way(WayPreset.STREET).named("Cross St").from(cross).north(300);
        scene.way(WayPreset.FOOTPATH).named("Station Path").from(path).north(200);
        SceneStop station = scene.stop("station", 600, 210);
        ScenePolygon polygon = scene.rectPolygon("pickup", 300, -100, 900, 150);
        scene.onDemand("flex1")
            .fromPolygon(polygon).pickupWindow(7 * 3600, 19 * 3600)
            .toStops(station).dropOffWindow(7 * 3600, 19 * 3600)
            .durationFactor(1.2).durationOffset(300);
        return scene;
    }

    @Test
    void buildAndVisualizeExampleScene () throws IOException {
        Scene scene = exampleScene();

        // Distances in scene meters are true spherical distances.
        // 800 meters along Main St should measure 800 meters on the ground.
        Coordinate mainStWest = new CoordinateXY(scene.lonForXY(0, 0), scene.latForY(0));
        Coordinate mainStEast = new CoordinateXY(scene.lonForXY(800, 0), scene.latForY(0));
        assertEquals(800, SphericalDistanceLibrary.fastDistance(mainStWest, mainStEast), 4);

        TransportNetwork network = scene.buildNetwork(tempDir);

        // The MapDB files were created under known names in the per-test temp dir.
        assertTrue(Files.exists(tempDir.resolve(Scene.OSM_DB_NAME)));
        assertTrue(Files.exists(tempDir.resolve(Scene.GTFS_DB_NAME)));

        // The on-demand trip is present in the index, with a pickup polygon and with the
        // drop-off stop group resolved to the station's stop index. Every field set on the
        // SceneOnDemand should survive rendering to GTFS and network building.
        assertNotNull(network.transitLayer.onDemandIndex);
        assertEquals(1, network.transitLayer.onDemandIndex.size());
        OnDemand onDemand = network.transitLayer.onDemandIndex.allServices().getFirst();
        assertNotNull(onDemand.fromPolygon);
        assertEquals(1, onDemand.toStopIndexes.length);
        assertEquals(0, onDemand.toStopIndexes[0]);
        assertEquals(7 * 3600, onDemand.fromWindowStart);
        assertEquals(19 * 3600, onDemand.fromWindowEnd);
        assertEquals(19 * 3600, onDemand.toWindowEnd);
        assertEquals(1.2, onDemand.durationFactor, 1e-9);
        assertEquals(300, onDemand.durationOffset, 1e-9);

        // The SVG diagram labels every declared entity. We write it to a file and read it back.
        Path svgFile = tempDir.resolve("example-scene.svg");
        scene.writeSvg(svgFile);
        String svg = Files.readString(svgFile);
        assertTrue(svg.startsWith("<svg"));
        for (String expected : new String[] {"Main St", "Cross St", "Station Path", "station", "pickup"}) {
            assertTrue(svg.contains(expected), "SVG should label " + expected);
        }
    }

}
