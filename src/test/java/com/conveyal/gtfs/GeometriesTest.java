package com.conveyal.gtfs;

import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;

import static com.conveyal.gtfs.Geometries.geometryFactory;
import static com.conveyal.gtfs.Geometries.getNetherlandsWithoutTexel;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Makes tests for the Netherlands geometry utils.
 * I added tests here because it substantially increases the coverage of the library since the file it's testing is so big.
 */
public class GeometriesTest {

    /**
     * Verify that a proper geometry is created using the getNetherlandsWithoutTexel method.
     * This method also calls the getNetherlands and getTexel methods, so we kill 3 birds with one stone here.
     */
    @Test
    public void canGetNetherlandsWithoutTexel() {
        Geometry geom = getNetherlandsWithoutTexel();
        assertTrue(geom.contains(geometryFactory.createPoint(new Coordinate(4.907812, 52.317809))));
        assertFalse(geom.contains(geometryFactory.createPoint(new Coordinate(4.816163, 53.099519))));
    }

}
