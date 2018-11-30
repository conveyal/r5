package com.conveyal.r5.point_to_point;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.ReadContext;
import net.minidev.json.JSONArray;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;

import static com.google.common.io.Files.asCharSource;
import static io.restassured.RestAssured.given;
import static io.restassured.module.jsv.JsonSchemaValidator.matchesJsonSchemaInClasspath;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.number.OrderingComparison.greaterThan;
import static org.hamcrest.number.OrderingComparison.greaterThanOrEqualTo;

public class PointToPointRouterServerTest {
    private static final String resourcesDir = "./src/test/resources/com/conveyal/r5/point_to_point";

    /**
     * Start up a point to point route server that will be queried in various tests
     */
    @BeforeClass
    public static void setUp() throws IOException {
        // create a temporary director and copy needed files there
        String[] files = {"osm.pbf", "terre-haute-gtfs.zip"};
        String tempDirPath = createTempDirWithFiles("pointServer", files);

        // build the network file
        String[] buildArgs = {"--build", tempDirPath};
        PointToPointRouterServer.main(buildArgs);

        // start the server
        String[] runArgs = {"--graphs", tempDirPath};
        PointToPointRouterServer.main(runArgs);
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
    public void canFetchMetadata() {
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
    public void canFetchReachedStops() {
        String response = given()
            .port(8080)
            .queryParam("mode", "WALK")
            .queryParam("fromLat", 39.465659)
            .queryParam("fromLon", -87.410839)
            .get("/reachedStops")
            .asString();

        // we need to use JSONPath here, because it's impossible to search for an object at
        // any position in an array with rest-assured's version of JSONPath.
        ReadContext ctx = JsonPath.parse(response);

        // expect a certain amount of reached stops
        assertThat(ctx.read("$.data.features.length()"), equalTo(30));

        // Search for the stop of Union Hospital
        JSONArray foundStops = ctx.read("$.data.features.[?(@.properties.name=='Bus Transfer Center')]");
        assertThat(foundStops.size(), equalTo(1));
        LinkedHashMap transferCenterStop = (LinkedHashMap) foundStops.get(0);

        // assert position of stop
        JSONArray coordinates = (JSONArray) ((LinkedHashMap)transferCenterStop.get("geometry")).get("coordinates");
        assertThat(coordinates.get(0), equalTo(-87.4058699));
        assertThat(coordinates.get(1), equalTo(39.46799));

        // assert properties of stop
        LinkedHashMap properties = (LinkedHashMap) transferCenterStop.get("properties");
        assertThat(properties.get("distance_m"), equalTo(772));
        assertThat(properties.get("duration_s"), equalTo(596));
    }

    /**
     * Assert that a trip plan with walking can be dones
     */
    @Test
    public void canPlanWalkTrip() {
        String response = given()
            .port(8080)
            .queryParam("mode", "WALK")
            .queryParam("fromLat", 39.465659)
            .queryParam("fromLon", -87.410839)
            .queryParam("toLat", 39.485515)
            .queryParam("toLon", -87.384223)
            .get("/plan")
            .asString();

        // we need to use JSONPath here, because we need to extract the data and dynamically compare features
        // any position in an array with rest-assured's version of JSONPath.
        ReadContext ctx = JsonPath.parse(response);

        // get all polylines
        JSONArray polylines = ctx.read("$.data.features");

        // expect at least 1 polyline, likely many more
        int numPolylines = polylines.size();
        assertThat(numPolylines, greaterThan(0));


        // expect an exact position of the first and last polylines
        LinkedHashMap firstPolyline = (LinkedHashMap) polylines.get(0);
        JSONArray firstPolylineCoordinates = (JSONArray)((LinkedHashMap)firstPolyline.get("geometry")).get("coordinates");
        JSONArray firstPolylineFirstCoordinate = (JSONArray)firstPolylineCoordinates.get(0);
        assertThat(firstPolylineFirstCoordinate.get(0), equalTo(-87.4099729));
        assertThat(firstPolylineFirstCoordinate.get(1), equalTo(39.4654342));
        LinkedHashMap lastPolyline = (LinkedHashMap) polylines.get(numPolylines - 1);
        JSONArray lastPolylineCoordinates = (JSONArray)((LinkedHashMap)lastPolyline.get("geometry")).get("coordinates");
        JSONArray lastPolylineLastCoordinate = (JSONArray)lastPolylineCoordinates.get(lastPolylineCoordinates.size() - 1);
        assertThat(lastPolylineLastCoordinate.get(0), equalTo(-87.3837375));
        assertThat(lastPolylineLastCoordinate.get(1), equalTo(39.4858394));

        // expect each polyline to have a distance greater than the last
        int curDistance = 0;
        for (int i = 0; i < numPolylines; i++) {
            int curPolylineDistance = (int)((LinkedHashMap)((LinkedHashMap)polylines.get(i)).get("properties")).get("distance");
            assertThat(curPolylineDistance, greaterThanOrEqualTo(curDistance));
            curDistance = curPolylineDistance;
        }
    }

    /**
     * Assert that a trip plan with walking can be dones
     */
    @Test
    public void canMakeOTPGraphQLQuery() throws IOException {
        String resp = given()
            .port(8080)
            .body(makeGraphQLQuery())
            .post("/otp/routers/default/index/graphql")
        .then()
            .body(matchesJsonSchemaInClasspath("com/conveyal/r5/point_to_point/otp-graphql-response.json"))
            .extract()
            .body()
            .asString();

        assertThat(resp, not(containsString("error")));
    }

    /**
     * Make a GraphQL query to use in the body of a post request to /otp/routers/default/index/graphql
     */
    private String makeGraphQLQuery() throws IOException {
        ObjectMapper mapper = new ObjectMapper();

        GraphQLQueryDTO query = new GraphQLQueryDTO();

        query.query = asCharSource(getResourceFile("graphql-query.txt"), Charset.defaultCharset()).read();

        VariablesDTO variables = new VariablesDTO();

        variables.accessModes = Arrays.asList("WALK", "BICYCLE", "BICYCLE_RENT", "CAR_PARK");
        variables.bikeSpeed = "8";
        variables.bikeTrafficStress = "4";
        variables.directModes = Arrays.asList("CAR", "WALK", "BICYCLE", "BICYCLE_RENT");
        variables.egressModes = Arrays.asList("WALK");
        variables.fromLat = "39.465659";
        variables.fromLon = "-87.410839";
        variables.fromTime = "2017-09-18T12:00:00.000Z";
        variables.toLat = "39.4767";
        variables.toLon = "-87.4052";
        variables.toTime = "2017-09-18T14:00:00.000Z";
        variables.transitModes = Arrays.asList("BUS", "RAIL", "SUBWAY", "TRAM");
        variables.walkSpeed = "3";

        query.variables = mapper.writeValueAsString(variables);

        return mapper.writeValueAsString(query);
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
        return (new File(String.join(File.separator, dirName, filename))).toPath();
    }

    private File getResourceFile(String filename) {
        return new File(String.join(File.separator, resourcesDir, filename));
    }
}
