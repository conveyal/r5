package com.conveyal.r5.analyst;

import org.geotools.geometry.jts.ReferencedEnvelope;
import org.junit.Test;

import java.io.FileOutputStream;
import java.io.OutputStream;

import static org.junit.Assert.*;

public class GridTest {

    @Test
    public void testGetMercatorEnvelopeMeters() throws Exception {
        // Southeastern Australia
        // Correct meter coordinates from http://www.maptiler.org/google-maps-coordinates-tile-bounds-projection/
        int zoom = 4;
        int xTile = 14;
        int yTile = 9;
        Grid grid = new Grid(zoom, 256, 256, 256 * yTile, 256 * xTile);
        ReferencedEnvelope envelope = grid.getMercatorEnvelopeMeters();
        assertEquals(15028131.257091936, envelope.getMinX(), 0.1);
        assertEquals(-5009377.085697312, envelope.getMinY(), 0.1);
        assertEquals(17532819.79994059, envelope.getMaxX(), 0.1);
        assertEquals(-2504688.542848654, envelope.getMaxY(), 0.1);

        // Cutting through Paris
        zoom = 5;
        xTile = 16;
        yTile = 11;
        grid = new Grid(zoom, 256, 256, 256 * yTile, 256 * xTile);
        envelope = grid.getMercatorEnvelopeMeters();
        assertEquals(0, envelope.getMinX(), 0.1);
        assertEquals(5009377.085697312, envelope.getMinY(), 0.1);
        assertEquals(1252344.271424327, envelope.getMaxX(), 0.1);
        assertEquals(6261721.357121639, envelope.getMaxY(), 0.1);

//        /**
//         * Make sure the Mercator projection works properly. Open the resulting file in GIS and
//         * compare with http://www.maptiler.org/google-maps-coordinates-tile-bounds-projection/
//         */
//        OutputStream outputStream = new FileOutputStream("test.tiff");
//        grid.writeGeotiff(outputStream);
//        outputStream.close();


    }

}