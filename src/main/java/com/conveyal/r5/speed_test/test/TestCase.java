package com.conveyal.r5.speed_test.test;

import com.conveyal.r5.speed_test.api.model.Itinerary;

import java.util.Collection;
import java.util.List;


/**
 * Hold all information about a test case and its results.
 */
public class TestCase {
    public final String id;
    public final String description;
    public final String origin;
    public final String fromPlace;
    public final double fromLat;
    public final double fromLon;
    public final String toPlace;
    public final double toLat;
    public final double toLon;
    public final String destination;
    final String transportType;
    final TestCaseResults results;

    TestCase(
            String id, String description, String fromPlace,
            double fromLat, double fromLon, String origin,
            String toPlace, double toLat, double toLon, String destination,
            String transportType,
            TestCaseResults testCaseResults
    ) {
        this.id = id;
        this.description = description;
        this.origin = origin;
        this.fromPlace = fromPlace;
        this.fromLat = fromLat;
        this.fromLon = fromLon;
        this.destination = destination;
        this.toPlace = toPlace;
        this.toLat = toLat;
        this.toLon = toLon;
        this.transportType = transportType;
        this.results = testCaseResults == null ? new TestCaseResults(id) : testCaseResults;
    }




    @Override
    public String toString() {
        return String.format("#%s %s - %s, (%.3f, %.3f) - (%.3f, %.3f)", id, origin, destination, fromLat, fromLon, toLat, toLon);
    }

    /**
     * Verify the result by matching it with the {@code expectedResult} from the csv file.
     */
    public void assertResult(Collection<Itinerary> itineraries) {
        results.matchItineraries(itineraries);

        if (results.failed()) {
            throw new TestCaseFailedException();
        }
    }

    /**
     * All test results are OK.
     */
    public boolean success() {
        return results.success();
    }

    public void printResults() {
        System.err.println(results.toString());
    }

    /**
     * The test case is not run or no itineraries found.
     */
    boolean notRun() {
        return results.noResults();
    }

    /**
     * List all results found for this testcase.
     */
    List<Result> actualResults() {
        return results.actualResults();
    }
}
