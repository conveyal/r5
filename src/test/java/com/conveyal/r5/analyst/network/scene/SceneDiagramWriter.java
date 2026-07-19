package com.conveyal.r5.analyst.network.scene;

import java.nio.file.Path;
import java.nio.file.Paths;

/// Writes the SVG diagram of every test scene into the scene package source directory, where
/// the diagrams are checked in as illustrations for the tests. Run the main method manually from
/// the repository root after changing any scene, then review and commit the updated diagrams.
class SceneDiagramWriter {

    private static final String OUTPUT_DIR = "src/test/java/com/conveyal/r5/analyst/network/scene";

    public static void main (String[] args) {
        Path outputDir = Paths.get(args.length > 0 ? args[0] : OUTPUT_DIR);

        write(StopLinkingTest.twoStreetScene(), outputDir, "twoStreetScene");
        write(SceneBuilderTest.exampleScene(), outputDir, "exampleScene");

        Scene pickup = new Scene();
        OnDemandStopAccessTest.drivableStreetNetwork(pickup, 10);
        write(pickup, outputDir, "drivableStreetNetwork10m");

        Scene hop = new Scene();
        OnDemandStopAccessTest.drivableStreetNetwork(hop, 200);
        write(hop, outputDir, "drivableStreetNetwork200m");

        Scene plaza = new Scene();
        OnDemandStopAccessTest.plazaNetwork(plaza);
        write(plaza, outputDir, "plazaNetwork");

        Scene island = new Scene();
        OnDemandStopAccessTest.islandNetwork(island);
        write(island, outputDir, "islandNetwork");

        Scene station = new Scene();
        OnDemandStopAccessTest.stationNetwork(station);
        write(station, outputDir, "stationNetwork");

        Scene villages = new Scene();
        OnDemandZoneAccessTest.twoVillagesNetwork(villages);
        write(villages, outputDir, "twoVillagesNetwork");
    }

    private static void write (Scene scene, Path outputDir, String baseName) {
        Path file = outputDir.resolve(baseName + ".svg");
        scene.writeSvg(file);
        System.out.println("Wrote " + file);
    }

}
