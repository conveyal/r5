package com.conveyal.r5.speed_test;

import com.csvreader.CsvReader;

import javax.validation.constraints.NotNull;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

class CsvTestCase {
    public int index;
    public String origin;
    public double fromLat;
    public double fromLon;
    public String destination;
    public double toLat;
    public double toLon;

    private String[] expectedResult;
    private List<AssertErrorMessage> errors = new ArrayList<>();


    @Override
    public String toString() {
        return String.format("#%d %s -> %s, (%.3f, %.3f) -> (%.3f, %.3f)", index, origin, destination, fromLat, fromLon, toLat, toLon);
    }

    static List<CsvTestCase> readTestCasesFromFile(File csvFile) throws IOException {
        List<CsvTestCase> testCases = new ArrayList<>();
        CsvReader csvReader = new CsvReader(csvFile.getAbsolutePath());
        csvReader.readRecord(); // Skip header
        int index = 0;

        while (csvReader.readRecord()) {
            CsvTestCase tc = new CsvTestCase();
            tc.index = index++;
            tc.origin = csvReader.get(4);
            tc.fromLat = Double.parseDouble(csvReader.get(2));
            tc.fromLon = Double.parseDouble(csvReader.get(3));
            tc.destination = csvReader.get(8);
            tc.toLat = Double.parseDouble(csvReader.get(6));
            tc.toLon = Double.parseDouble(csvReader.get(7));
            tc.expectedResult = expectedResult(csvReader.get(10));
            testCases.add(tc);
        }
        return testCases;
    }


    private static String[] expectedResult(String text) {
        if(text == null || text.isEmpty()) {
            return null;
        }
        return text.split("\\s*\\|\\s*");
    }

    /**
     * Verify the result by matching it with the {@code expectedResult} from the csv file.
     */
    void assertResult(String[] results) {

        // If no expected result exist, then log the results os it ca be used and copied into the
        // CSV input files
        if(expectedResult == null) {
            logTheResult(results);
            return;
        }

        boolean[] resultMatch = new boolean[results.length];

        for (String exp : expectedResult) {
            int i = find(exp, results);
            if(i == -1) {
                errors.add(new AssertErrorMessage(exp, "- Unable to find expected trip:"));
            }
            else {
                resultMatch[i] = true;
            }
        }

        // Log all results not matched
        for (int i=0; i<resultMatch.length; ++i) {
            if(!resultMatch[i]) {
                errors.add(new AssertErrorMessage(results[i], "+ Trip returned but not expected:"));
            }
        }

        if(failed()) {
            throw new IllegalStateException("Trip errors");
        }
    }

    String errorDetails() {
        return  errors.stream().sorted().map(s -> s + "\n").reduce((s, t) -> s + t).orElse("");
    }

    private void logTheResult(String[] results) {
        System.err.println("INFO! The following result can be added to the test case (csv-input-files) as expectedResult:");
        boolean first = true;
        for (String result : results) {
            if(!first) System.err.print(" | ");
            System.err.print(result);
            first = false;
        }
        System.err.println();
    }

    /** Find the expected in the results. */
    private static int find(String expected, String[] results) {
        for (int i = 0; i < results.length; i++) {
            if(expected.equals(results[i])) return i;
        }
        return -1;
    }

    public boolean failed() {
        return !errors.isEmpty();
    }

    private static class AssertErrorMessage implements Comparable<AssertErrorMessage>{
        private static int maxLength = 0;
        String msg;
        String trip;

        AssertErrorMessage(String trip, String msg) {
            this.msg = msg;
            this.trip = trip;
            maxLength = Math.max(msg.length(), maxLength);
        }

        @Override
        public String toString() {
            return String.format("%-"+(maxLength+1)+"s  %s", msg, trip);
        }

        @Override
        public int compareTo(@NotNull AssertErrorMessage o) {
            return trip.compareTo(o.trip);
        }
    }
}
