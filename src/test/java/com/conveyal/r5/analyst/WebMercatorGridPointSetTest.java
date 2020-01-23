package com.conveyal.r5.analyst;

import gnu.trove.list.TIntList;
import junit.framework.TestCase;
import org.junit.Test;
import org.locationtech.jts.geom.Envelope;

import java.util.Arrays;
import java.util.List;

import static com.conveyal.r5.common.GeometryUtils.floatingWgsEnvelopeToFixed;

/**
 * Test the web mercator grid pointset.
 */
public class WebMercatorGridPointSetTest extends TestCase {

    /** Test that latitude/longitude to pixel conversions are correct */
    @Test
    public static void testLatLonPixelConversions () {
        // offsets and width/height not needed for calculations
        WebMercatorGridPointSet ps = new WebMercatorGridPointSet(
            WebMercatorGridPointSet.DEFAULT_ZOOM, 0,0, 100, 100, null
        );

        for (double lat : new double [] { -75, -25, 0, 25, 75 }) {
            assertEquals(lat, ps.pixelToLat(ps.latToPixel(lat)), 1e-2);
        }

        for (double lon : new double [] { -175, -90, 0, 90, 175}) {
            assertEquals(lon, ps.pixelToLon(ps.lonToPixel(lon)), 1e-2);
        }
    }

    /** Test that we can find pixel numbers for envelopes intersecting a gridded PointSet. */
    @Test
    public static void testPixelsInEnvelope () {

        // Envelope and grid 1 degree high and 5 degrees wide, in the ocean south of Africa.
        Envelope gridEnvelope = new Envelope(10, 15, -45, -44);
        WebMercatorGridPointSet gridPointSet = new WebMercatorGridPointSet(gridEnvelope);

        // Entirely outside the grid, to the north of it.
        Envelope outsideNorth = new Envelope(12, 13, -43.5, -43.4);

        // Entirely outside the grid, to the south of it.
        Envelope outsideSouth = new Envelope(13, 14, -46, -45.9);

        // Entirely outside the grid, to the east of it.
        Envelope outsideEast = new Envelope(15.9, 16, -45, -44);

        // Entirely outside the grid, to the west of it.
        Envelope outsideWest = new Envelope(9, 9.1, -45, -44);

        // Entirely inside the grid, on the west side
        Envelope insideWest = new Envelope(11, 12, -44.4, -44.6);

        // Entirely inside the grid, on the east side
        Envelope insideEast = new Envelope(13, 14, -44.5, -44.7);

        // Partially overlapping the grid on the north edge
        Envelope partialNorth = new Envelope(12, 12.5, -44.1, -43.9);

        // Partially overlapping the grid on the south edge
        Envelope partialSouth = new Envelope(13, 13.5, -45.1, -44.9);

        // Partially overlapping the grid on the east edge
        Envelope partialEast = new Envelope(14.9, 15.1, -44.4, -44.6);

        // Partially overlapping the grid on the west edge
        Envelope partialWest = new Envelope(9.9, 10.1, -44.2, -44.3);

        List<Envelope> envelopes = Arrays.asList(
                outsideNorth, outsideSouth, outsideEast, outsideWest,
                insideEast, insideWest,
                partialNorth, partialSouth, partialEast, partialWest
        );

        // Track the number of test envelopes in each category to ensure the test is correctly formulated.
        int outsideCount = 0;
        int insideCount = 0;
        int partialCount = 0;

        for (Envelope envelope : envelopes) {
            TIntList points = gridPointSet.getPointsInEnvelope(floatingWgsEnvelopeToFixed(envelope));
            if (gridEnvelope.contains(envelope)) {
                insideCount += 1;
                assertTrue(points.size() > 30_000);
            } else if (gridEnvelope.intersects(envelope)) {
                partialCount += 1;
                assertTrue(points.size() > 1_000);
                assertTrue(points.size() < 10_000);
            } else {
                outsideCount += 1;
                assertTrue(points.isEmpty());
            }
            // Every reported point index should fall within the total size of the grid.
            points.forEach(p -> {
               assertTrue(p >= 0);
               assertTrue(p < gridPointSet.featureCount());
               return true;
            });
        }

        // Check that our envelopes actually had the intended characteristics (according to JTS predicates).
        assertEquals(outsideCount, 4);
        assertEquals(insideCount, 2);
        assertEquals(partialCount, 4);
    }

}