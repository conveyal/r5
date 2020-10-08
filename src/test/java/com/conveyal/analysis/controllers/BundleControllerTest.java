package com.conveyal.analysis.controllers;

import com.conveyal.analysis.AnalysisServerTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.bson.types.ObjectId;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;

import static com.conveyal.analysis.TestUtils.getResourceFileName;
import static com.conveyal.analysis.TestUtils.objectIdInResponse;
import static com.conveyal.analysis.TestUtils.parseJson;
import static com.conveyal.analysis.TestUtils.removeDynamicValues;
import static com.conveyal.analysis.TestUtils.removeKeysAndValues;
import static com.conveyal.analysis.TestUtils.zipFolderFiles;
import static com.zenika.snapshotmatcher.SnapshotMatcher.matchesSnapshot;
import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

public class BundleControllerTest {
    private static final Logger LOG = LoggerFactory.getLogger(BundleControllerTest.class);
    private static boolean setUpIsDone = false;
    private static String regionId = new ObjectId().toString();
    private static String simpleGtfsZipFileName;

    /**
     * Prepare and start a testing-specific web server and create a region for the bundle to be uploaded to
     * @throws Exception
     */
    @BeforeClass
    public static void setUp() throws Exception {
        if (setUpIsDone) {
            return;
        }

        // start server if it isn't already running
        AnalysisServerTest.setUp();

        // zip up the gtfs folder
        simpleGtfsZipFileName = zipFolderFiles("fake-agency");

        setUpIsDone = true;
    }

    @Test
    public void canCreateReadAndDeleteABundle () throws IOException, InterruptedException {
        // create the bundle
        JsonNode createdJson = parseJson(
            given()
                .port(7070)
                .contentType("multipart/form-data")
                .multiPart("bundleName", "test-bundle")
                .multiPart("regionId", regionId)
                .multiPart("feedGroup", new File(simpleGtfsZipFileName))
                .multiPart("osm", new File(getResourceFileName("felton.pbf")))
                .post("api/bundle")
            .then()
                .extract()
                .response()
                .asString()
        );
        String bundleId = createdJson.get("_id").asText();

        // wait 3 seconds so that the bundle can be processed (there are some async calculations that can change the
        // data of the bundle over time).
        LOG.info("waiting 3 seconds for bundle to be processed");
        Thread.sleep(3000);

        // Then verify the bundle can be fetched and snapshot the data.
        JsonNode fetchedJson = parseJson(
            given()
                .port(7070)
                .get("api/bundle/" + bundleId)
            .then()
                .body("_id", equalTo(bundleId))
                .extract()
                .response()
                .asString()
        );
        canCreateBundle(fetchedJson);

        // delete the bundle and verify that the bundle was returned
        given()
            .port(7070)
            .delete("api/bundle/" + bundleId)
        .then()
            .body("_id", equalTo(bundleId));

        // verify the bundle no longer exists by fetching all bundles and verifying that the bundle is no longer present
        assertThat(
            objectIdInResponse(
                given()
                    .port(7070)
                    .get("api/bundle"),
                bundleId
            ),
            equalTo(false)
        );
    }

    /**
     * Assert creation of region in this method, so it gets a proper snapshot name.
     */
    private void canCreateBundle(JsonNode json) {
        assertThat(json.get("regionId").asText(), equalTo(regionId));
        removeDynamicValues(json);
        // remove additional key/values that change between each test run
        removeKeysAndValues(json, new String[]{"bundleScopedFeedId", "feedId", "regionId"});
        assertThat(json, matchesSnapshot());
    }
}
