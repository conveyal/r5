package com.conveyal.analysis.controllers;

import com.conveyal.analysis.AnalysisServerTest;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

import static com.conveyal.analysis.TestUtils.parseJson;
import static com.conveyal.analysis.TestUtils.removeDynamicValues;
import static com.zenika.snapshotmatcher.SnapshotMatcher.matchesSnapshot;
import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;

public class TimetableControllerTest {
    private static final Logger LOG = LoggerFactory.getLogger(TimetableControllerTest.class);
    private static boolean setUpIsDone = false;

    /**
     * Prepare and start a testing-specific web server
     * @throws Exception
     */
    @BeforeClass
    public static void setUp() throws Exception {
        if (setUpIsDone) {
            return;
        }

        // start server if it isn't already running
        AnalysisServerTest.setUp();

        // populate
        setUpIsDone = true;
    }

    /**
     * Make sure the timetables endpoint responds with expected data.
     */
    @Test
    public void canReturnTimetables() throws IOException {
        JsonNode json = parseJson(
            given()
                .port(7070)
                .get("/api/timetables")
            .then()
                .extract()
                .response()
                .asString()
        );
        removeDynamicValues(json);
        assertThat(json, matchesSnapshot());
    }
}
