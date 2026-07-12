package com.conveyal.gtfs.geom;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PointInPolygonTesterTest {

    /// A unit square with corners at (0,0) and (1,1), as packed lon/lat coordinates forming a
    /// closed ring, exercising the CPolygon constructor (which converts through JTS internally).
    private static PointInPolygonTester unitSquareTester () {
        double[] ring = {0, 0, 1, 0, 1, 1, 0, 1, 0, 0};
        return new PointInPolygonTester(new CPolygon(ring));
    }

    @Test
    void containsInteriorPoints () {
        PointInPolygonTester tester = unitSquareTester();
        assertTrue(tester.contains(0.5, 0.5));
        assertTrue(tester.contains(0.001, 0.999));
    }

    @Test
    void excludesExteriorPoints () {
        PointInPolygonTester tester = unitSquareTester();
        assertFalse(tester.contains(-0.5, 0.5));
        assertFalse(tester.contains(1.5, 0.5));
        assertFalse(tester.contains(0.5, 2));
    }

    /// Boundary points are excluded, matching JTS contains() semantics.
    @Test
    void excludesBoundaryPoints () {
        PointInPolygonTester tester = unitSquareTester();
        assertFalse(tester.contains(0, 0));
        assertFalse(tester.contains(0.5, 0));
        assertFalse(tester.contains(1, 0.5));
    }

    /// The internal Coordinate is reused across calls.
    /// Results must be independent of earlier queries.
    @Test
    void reusedCoordinateDoesNotLeakState () {
        PointInPolygonTester tester = unitSquareTester();
        for (int i = 0; i < 100; i++) {
            assertTrue(tester.contains(0.5, 0.5));
            assertFalse(tester.contains(5, 5));
        }
    }

}
