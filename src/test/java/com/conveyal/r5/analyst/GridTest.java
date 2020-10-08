package com.conveyal.r5.analyst;

import org.geotools.geometry.jts.ReferencedEnvelope;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Random;

import static org.junit.Assert.assertEquals;

public class GridTest {

    private static final long SEED = 4321;
    private static final int N_ITERATIONS = 20;
    private static final int MAX_GRID_WIDTH_PIXELS = 1024;
    private static final double MAX_AMOUNT = 500;

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

    @Test
    public void gridSerializationRoundTripTest () throws Exception {
        Random random = new Random(SEED);
        serializationTestLoop(random,true);
        serializationTestLoop(random,false);
    }

    private void serializationTestLoop (Random random, boolean wholeNumbersOnly) throws Exception {
        final double tolerance = wholeNumbersOnly ? 0 : 0.5;
        for (int i = 0; i < N_ITERATIONS; i++) {
            Grid gridA = generateRandomGrid(random, wholeNumbersOnly);
            ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
            gridA.write(byteArrayOutputStream);
            Grid gridB = Grid.read(new ByteArrayInputStream(byteArrayOutputStream.toByteArray()));
            assertGridSemanticEquals(gridA, gridB, tolerance);
        }
    }


    private static Grid generateRandomGrid (Random random, boolean wholeNumbersOnly) {
        final int zoom = 9;
        final int worldWidthPixels = 2 << 8 + zoom;

        int west = random.nextInt(worldWidthPixels - MAX_GRID_WIDTH_PIXELS);
        int north = random.nextInt(worldWidthPixels - MAX_GRID_WIDTH_PIXELS);
        int width = random.nextInt(MAX_GRID_WIDTH_PIXELS) + 1;
        int height = random.nextInt(MAX_GRID_WIDTH_PIXELS) + 1;

        Grid grid = new Grid(zoom, width, height, north, west);
        for (int y = 0; y < grid.height; y++) {
            for (int x = 0; x < grid.width; x++) {
                double amount = random.nextDouble() * MAX_AMOUNT;
                if (wholeNumbersOnly) {
                    amount = Math.round(amount);
                }
                grid.grid[x][y] = amount;
            }
        }
        return grid;
    }

    private static void assertGridSemanticEquals(Grid g1, Grid g2, double tolerance) {
        // Note that the name field is excluded because it does not survive serialization.
        assertEquals(g1.zoom, g2.zoom);
        assertEquals(g1.north, g2.north);
        assertEquals(g1.west, g2.west);
        assertEquals(g1.width, g2.width);
        assertEquals(g1.height, g2.height);
        assertArrayEquals(g1.grid, g2.grid, tolerance);
    }

    /**
     * Compare two 2D arrays of doubles with tolerance. This method is apparently not provided by junit.Assert.
     */
    private static void assertArrayEquals(double[][] a1, double[][]a2, double tolerance) {
        assertEquals(a1.length, a2.length);
        for (int i = 0; i < a1.length; i++) {
            double[] b1 = a1[i];
            double[] b2 = a2[i];
            assertEquals(b1.length, b2.length);
            for (int j = 0; j < b1.length; j++) {
                assertEquals(b1[j], b2[j], tolerance);
            }
        }
    }

}
