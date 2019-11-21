package com.conveyal.r5.analyst;

import junit.framework.TestCase;
import org.junit.Test;

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
}