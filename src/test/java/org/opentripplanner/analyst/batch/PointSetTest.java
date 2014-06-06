package org.opentripplanner.analyst.batch;

import junit.framework.TestCase;
import org.opentripplanner.analyst.PointSet;

import java.io.IOException;

public class PointSetTest extends TestCase {

    public void testPointSets() throws IOException {
        PointSet schools = PointSet.fromCsv("src/test/resources/pointset/schools.csv");
        assertNotNull(schools);
        assertEquals(schools.capacity, 9);
    }

    /** Factory method should return null but not throw an exception on malformed CSV. */
    public void testBogusCSV() throws IOException {
        PointSet points = PointSet.fromCsv("src/test/resources/pointset/bogus.csv");
        assertNull(points);
    }

}
