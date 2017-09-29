package com.conveyal.r5.point_to_point;

import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.ReadContext;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

public class PointToPointRouterServerTest {
    private static final String resourcesDir = "./src/test/resources/com/conveyal/r5/point_to_point";


    /**
     * Start up a point to point route server that will be queried in various tests
     */
    @BeforeClass
    public static void setUp() throws IOException {
        // create a temporary director and copy needed files there
        String[] files = {"network.dat", "osm.mapdb", "osm.mapdb.p"};
        String tempDirPath = createTempDirWithFiles("pointServer", files);

        // start the server
        String[] args = {"--graphs", tempDirPath};
        PointToPointRouterServer.main(args);
    }

    /**
     * Make sure that a network file can get built.
     */
    @Test
    public void canBuildANetwork() throws IOException {
        // create a temporary director and copy needed files there
        String[] files = {"osm.pbf", "terre-haute-gtfs.zip"};
        String tempDirPath = createTempDirWithFiles("canBuildANetwork", files);

        // build the network file
        String[] args = {"--build", tempDirPath};
        PointToPointRouterServer.main(args);

        // assert that network.dat file was written
        File networkDatFile = new File(tempDirPath + "/network.dat");
        assertThat(networkDatFile.exists(), is(true));
    }

    /**
     * Assert that metadata route works
     */
    @Test
    public void metadata() {
        given()
            .port(8080)
            .get("/metadata")
        .then()
            .body("name", equalTo("default"))
            .body("envelope.area", equalTo(0.18513083f));
    }

    /**
     * Assert that reached stops route works
     */
    @Test
    public void reachedStops() {
        String response = given()
            .port(8080)
            .queryParam("mode", "WALK")
            .queryParam("fromLat", 39.465659)
            .queryParam("fromLon", -87.410839)
            .get("/reachedStops")
            .asString();

        ReadContext ctx = JsonPath.parse(response);

        assertThat(ctx.read("$.data.features.length()"), equalTo(33));

        // Search for the stop of Union Hospital
        String stopPrefix = "$.data.features.[?(@.properties.name=='Union Hospital')]";

        // the indexed selector at the end in the following line is actually getting the first result in the above query
        // therefore I write two selectors.  Weird quirk of jsonpath.
        List<Double> latCoords = ctx.read(stopPrefix + ".geometry.coordinates[0]");
        List<Double> lonCoords = ctx.read(stopPrefix + ".geometry.coordinates[1]");

        assertThat(latCoords.get(0), equalTo(-87.40889));
        assertThat(lonCoords.get(0), equalTo(39.48484));
    }

    /**
     * Create a temporary directory and copy the specified files to said temporary directory.
     */
    public static String createTempDirWithFiles(String tempDirPrefix, String[] files) throws IOException {
        Path tempDir = Files.createTempDirectory(tempDirPrefix);
        String tempDirPath = tempDir.toString();
        for (int i = 0; i < files.length; i++) {
            Files.copy(
                makePath(resourcesDir, files[i]),
                makePath(tempDirPath , files[i])
            );
        }
        return tempDirPath;
    }

    /**
     * Get the Path from strings of a directory and a filename
     */
    public static Path makePath(String dirName, String filename) {
        return (new File(dirName + "/" + filename)).toPath();
    }
}
