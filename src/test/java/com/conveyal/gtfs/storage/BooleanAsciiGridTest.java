package com.conveyal.gtfs.storage;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BooleanAsciiGridTest {

    /**
     * Check that locations known to have high population density return true and those with known
     * low population density return false.
     */
    @Test
    public void testEarthPopulationGrid() {
        BooleanAsciiGrid asciiGrid = BooleanAsciiGrid.forEarthPopulation();
        assertTrue("Paris has high population density.", asciiGrid.getValueForCoords(2.352, 48.8566));
        assertTrue("Tokyo has high population density.", asciiGrid.getValueForCoords(139.6917,35.6895));
        assertFalse("Null Island has low population density.", asciiGrid.getValueForCoords(0, 0));
        assertFalse("The South Atlantic Ocean has low population density.", asciiGrid.getValueForCoords(-15.554475, -28.306585));
        assertTrue("Hong Kong has high population density.", asciiGrid.getValueForCoords(114.175911, 22.272639));
        assertFalse("Antarctica has low population density.", asciiGrid.getValueForCoords(75.502410, -79.204778));
        assertTrue("Hyderabad has high population density.", asciiGrid.getValueForCoords(78.1271653, 17.4128074));
        assertTrue("Buenos Aires has high population density.", asciiGrid.getValueForCoords(-58.4428545, -34.6202982));
        assertTrue("Maui has significant population density.", asciiGrid.getValueForCoords(-156.4915454, 20.8751302));
        assertFalse("The South Pacific Ocean has low population density.", asciiGrid.getValueForCoords(-149.7023417, -44.7672362));
    }
}