package com.conveyal.r5.point_to_point;

import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.ReadContext;
import net.minidev.json.JSONArray;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;

import static io.restassured.RestAssured.given;
import static io.restassured.module.jsv.JsonSchemaValidator.matchesJsonSchemaInClasspath;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
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
        assertThat(ctx.read("$.data.features.length()"), equalTo(33));

        // Search for the stop of Union Hospital
        JSONArray foundStops = ctx.read("$.data.features.[?(@.properties.name=='Union Hospital')]");
        assertThat(foundStops.size(), equalTo(1));
        LinkedHashMap unionHospitalStop = (LinkedHashMap) foundStops.get(0);

        // assert position of stop
        JSONArray coordinates = (JSONArray) ((LinkedHashMap)unionHospitalStop.get("geometry")).get("coordinates");
        assertThat(coordinates.get(0), equalTo(-87.40889));
        assertThat(coordinates.get(1), equalTo(39.48484));

        // assert properties of stop
        LinkedHashMap properties = (LinkedHashMap) unionHospitalStop.get("properties");
        assertThat(properties.get("distance_m"), equalTo(2284));
        assertThat(properties.get("duration_s"), equalTo(1768));
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
    public void canMakeOTPGraphQLQuery() {
        given()
            .port(8080)
            .body(makeGraphQLQuery())
            .post("/otp/routers/default/index/graphql")
        .then()
            .body(matchesJsonSchemaInClasspath("com/conveyal/r5/point_to_point/otp-graphql-response.json"));
    }

    /**
     * Make a GraphQL query to use in the body of a post request to /otp/routers/default/index/graphql
     */
    private String makeGraphQLQuery() {
        String accessModes = "WALK,BICYCLE,BICYCLE_RENT,CAR_PARK";
        String bikeSpeed = "8";
        String bikeTrafficStress = "4";
        String directModes = "CAR,WALK,BICYCLE,BICYCLE_RENT";
        String egressModes = "WALK";
        String fromLat = "39.465659";
        String fromLon = "-87.410839";
        String fromTime = "2017-09-18T12:00:00.000Z";
        String toLat = "39.4767";
        String toLon = "-87.4052";
        String toTime = "2017-09-18T14:00:00.000Z";
        String transitModes = "BUS,RAIL,SUBWAY,TRAM";
        String walkSpeed = "3";

        return "{" +
            "  \"query\": \"query requestPlan(\\n" +
            "    $fromLat: Float!, \\n" +
            "    $fromLon: Float!, \\n" +
            "    $toLat: Float!, \\n" +
            "    $toLon: Float!, \\n" +
            "    $fromTime: ZonedDateTime!, \\n" +
            "    $toTime: ZonedDateTime!, \\n" +
            "    $bikeSpeed:Float!, \\n" +
            "    $walkSpeed:Float!\\n" +
            "  ) {\\n" +
            "    plan(\\n" +
            "      minBikeTime: 1, \\n" +
            "      bikeTrafficStress: " + bikeTrafficStress + ", \\n" +
            "      fromLat: $fromLat, \\n" +
            "      fromLon: $fromLon, \\n" +
            "      toLat: $toLat, \\n" +
            "      toLon: $toLon, \\n" +
            "      fromTime: $fromTime, \\n" +
            "      toTime: $toTime, \\n" +
            "      directModes: [" + directModes + "], \\n" +
            "      accessModes: [" + accessModes + "], \\n" +
            "      egressModes: [" + egressModes + "], \\n" +
            "      transitModes: [" + transitModes + "], \\n" +
            "      bikeSpeed: $bikeSpeed, \\n" +
            "      walkSpeed: $walkSpeed\\n" +
            "    ) {\\n" +
            "      patterns {\\n" +
            "        tripPatternIdx\\n" +
            "        routeId\\n" +
            "        routeIdx\\n" +
            "        directionId\\n" +
            "        stops {\\n" +
            "          stopId\\n" +
            "          name\\n" +
            "          lat\\n" +
            "          lon\\n" +
            "        }\\n" +
            "        trips {\\n" +
            "          tripId\\n" +
            "          serviceId\\n" +
            "          bikesAllowed\\n" +
            "        }\\n" +
            "      }\\n" +
            "      options {\\n" +
            "        summary\\n" +
            "        itinerary {\\n" +
            "          waitingTime\\n" +
            "          walkTime\\n" +
            "          distance\\n" +
            "          transfers\\n" +
            "          duration\\n" +
            "          transitTime\\n" +
            "          startTime\\n" +
            "          endTime\\n" +
            "          connection {\\n" +
            "            access\\n" +
            "            egress\\n" +
            "            transit {\\n" +
            "              pattern\\n" +
            "              time\\n" +
            "            }\\n" +
            "          }\\n" +
            "        }\\n" +
            "        transit {\\n" +
            "          from {\\n" +
            "            name\\n" +
            "            stopId\\n" +
            "            lon\\n" +
            "            lat\\n" +
            "          }\\n" +
            "          to {\\n" +
            "            name\\n" +
            "            stopId\\n" +
            "            lon\\n" +
            "            lat\\n" +
            "          }\\n" +
            "          mode\\n" +
            "          routes {\\n" +
            "            id\\n" +
            "            description\\n" +
            "            routeIdx\\n" +
            "            shortName\\n" +
            "            mode\\n" +
            "            routeColor\\n" +
            "            textColor\\n" +
            "            url\\n" +
            "            agencyName\\n" +
            "          }\\n" +
            "          segmentPatterns {\\n" +
            "            patternId\\n" +
            "            patternIdx\\n" +
            "            routeIdx\\n" +
            "            fromIndex\\n" +
            "            toIndex\\n" +
            "            nTrips\\n" +
            "            fromArrivalTime\\n" +
            "            fromDepartureTime\\n" +
            "            toArrivalTime\\n" +
            "            toDepartureTime\\n" +
            "            tripId\\n" +
            "          }\\n" +
            "          middle {\\n" +
            "            mode\\n" +
            "            duration\\n" +
            "            distance\\n" +
            "            geometryPolyline\\n" +
            "          }\\n" +
            "          rideStats {\\n" +
            "            min\\n" +
            "            avg\\n" +
            "            max\\n" +
            "            num\\n" +
            "          }\\n" +
            "          waitStats {\\n" +
            "            min\\n" +
            "            avg\\n" +
            "            max\\n" +
            "            num\\n" +
            "          }\\n" +
            "        }\\n" +
            "        access {\\n" +
            "          mode\\n" +
            "          duration\\n" +
            "          distance\\n" +
            "          streetEdges {\\n" +
            "            edgeId\\n" +
            "            geometryPolyline\\n" +
            "            distance\\n" +
            "            mode\\n" +
            "            streetName\\n" +
            "            relativeDirection\\n" +
            "            absoluteDirection\\n" +
            "            stayOn\\n" +
            "            area\\n" +
            "            exit\\n" +
            "            bogusName\\n" +
            "            bikeRentalOnStation {\\n" +
            "              id\\n" +
            "              name\\n" +
            "              lat\\n" +
            "              lon\\n" +
            "            }\\n" +
            "            bikeRentalOffStation {\\n" +
            "              id\\n" +
            "              name\\n" +
            "              lat\\n" +
            "              lon\\n" +
            "            }\\n" +
            "            parkRide {\\n" +
            "              id\\n" +
            "              name\\n" +
            "              capacity\\n" +
            "              lon\\n" +
            "              lat\\n" +
            "            }\\n" +
            "          }\\n" +
            "        }\\n" +
            "        egress {\\n" +
            "          mode\\n" +
            "          duration\\n" +
            "          distance\\n" +
            "          streetEdges {\\n" +
            "            edgeId\\n" +
            "            distance\\n" +
            "            geometryPolyline\\n" +
            "            mode\\n" +
            "            streetName\\n" +
            "            relativeDirection\\n" +
            "            absoluteDirection\\n" +
            "            stayOn\\n" +
            "            area\\n" +
            "            exit\\n" +
            "            bogusName\\n" +
            "            bikeRentalOnStation {\\n" +
            "              id\\n" +
            "              name\\n" +
            "              lon\\n" +
            "              lat\\n" +
            "            }\\n" +
            "          }\\n" +
            "        }\\n" +
            "        fares {\\n" +
            "          type\\n" +
            "          low\\n" +
            "          peak\\n" +
            "          senior\\n" +
            "          transferReduction\\n" +
            "          currency\\n" +
            "        }\\n" +
            "      }\\n" +
            "    }\\n" +
            "  }\"," +
            "  \"variables\":\"{" +
            "    \\\"bikeSpeed\\\":" + bikeSpeed + "," +
            "    \\\"fromLat\\\":" + fromLat + "," +
            "    \\\"fromLon\\\":" + fromLon + "," +
            "    \\\"fromTime\\\": \\\"" + fromTime + "\\\"," +
            "    \\\"toLat\\\":" + toLat + "," +
            "    \\\"toLon\\\":" + toLon + "," +
            "    \\\"toTime\\\":\\\"" + toTime + "\\\"," +
            "    \\\"walkSpeed\\\":" + walkSpeed +
            "    }\"" +
            "}";
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
