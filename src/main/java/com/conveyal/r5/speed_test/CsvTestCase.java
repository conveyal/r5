package com.conveyal.r5.speed_test;

import com.csvreader.CsvReader;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

class CsvTestCase {
    public String origin;
    public double fromLat;
    public double fromLon;
    public String destination;
    public double toLat;
    public double toLon;


    @Override
    public String toString() {
        return String.format("%s -> %s, (%.3f, %.3f) -> (%.3f, %.3f)", origin, destination, fromLat, fromLon, toLat, toLon);
    }

    static List<CsvTestCase> getCoordPairs(File csvFile) throws IOException {
        List<CsvTestCase> testCases = new ArrayList<>();
        CsvReader csvReader = new CsvReader(csvFile.getAbsolutePath());
        csvReader.readRecord(); // Skip header
        while (csvReader.readRecord()) {
            CsvTestCase tc = new CsvTestCase();
            tc.origin = csvReader.get(4);
            tc.fromLat = Double.parseDouble(csvReader.get(2));
            tc.fromLon = Double.parseDouble(csvReader.get(3));
            tc.destination = csvReader.get(8);
            tc.toLat = Double.parseDouble(csvReader.get(6));
            tc.toLon = Double.parseDouble(csvReader.get(7));
            testCases.add(tc);
        }
        return testCases;
    }
}
