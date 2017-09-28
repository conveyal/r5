package com.conveyal.r5.point_to_point;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

public class PointToPointRouterServerTest {
    private static final String resourcesDir = "./src/test/resources/com/conveyal/r5/point_to_point";

    @Test
    public void canBuildANetwork() throws IOException {
        // create a temporary director and move needed files there
        Path tempDir = Files.createTempDirectory("canBuildANetwork");
        String tempDirPath = tempDir.toString();
        Files.copy(
            makePath(resourcesDir, "osm.pbf"),
            makePath(tempDirPath , "osm.pbf")
        );
        Files.copy(
            makePath(resourcesDir, "terre-haute-gtfs.zip"),
            makePath(tempDirPath , "terre-haute-gtfs.zip")
        );

        // build the network file
        String[] args = {"--build", tempDirPath};
        PointToPointRouterServer.main(args);

        // assert that network.dat file was written
        File networkDatFile = new File(tempDirPath + "/network.dat");
        assertThat(networkDatFile.exists(), is(true));
    }

    Path makePath(String dirName, String filename) {
        return (new File(dirName + "/" + filename)).toPath();
    }
}
