package com.conveyal.gtfs.storage;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class BooleanAsciiGridTest {

    /**
     * Check that locations known to have high population density return true and those with known
     * low population density return false.
     */
    @Test
    public void testEarthPopulationGrid() {
        BooleanAsciiGrid asciiGrid = BooleanAsciiGrid.forEarthPopulation();
        Assertions.assertTrue(asciiGrid.getValueForCoords(2.352, 48.8566), "Paris has high population density.");
        Assertions.assertTrue(asciiGrid.getValueForCoords(139.6917,35.6895), "Tokyo has high population density.");
        Assertions.assertFalse(asciiGrid.getValueForCoords(0, 0), "Null Island has low population density.");
        Assertions.assertFalse(asciiGrid.getValueForCoords(-15.554475, -28.306585), "The South Atlantic Ocean has low population density.");
        Assertions.assertTrue(asciiGrid.getValueForCoords(114.175911, 22.272639), "Hong Kong has high population density.");
        Assertions.assertFalse(asciiGrid.getValueForCoords(75.502410, -79.204778), "Antarctica has low population density.");
        Assertions.assertTrue(asciiGrid.getValueForCoords(78.1271653, 17.4128074), "Hyderabad has high population density.");
        Assertions.assertTrue(asciiGrid.getValueForCoords(-58.4428545, -34.6202982), "Buenos Aires has high population density.");
        Assertions.assertTrue(asciiGrid.getValueForCoords(-156.4915454, 20.8751302), "Maui has significant population density.");
        Assertions.assertFalse(asciiGrid.getValueForCoords(-149.7023417, -44.7672362), "The South Pacific Ocean has low population density.");
    }
}