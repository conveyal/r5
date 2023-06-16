package com.conveyal.r5.analyst;

import com.google.common.io.Resources;
import org.geotools.geometry.jts.ReferencedEnvelope;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the Grid class, which holds destination counts in tiled spherical mercator pixels.
 */
public class GridTest {

    private static final long SEED = 4321;
    private static final int N_ITERATIONS = 20;
    private static final int MAX_GRID_WIDTH_PIXELS = 1024;
    private static final double MAX_AMOUNT = 500;

    @Test
    public void testGetMercatorEnvelopeMeters() throws Exception {
        // Southeastern Australia
        // Reference coordinates in meters from http://www.maptiler.org/google-maps-coordinates-tile-bounds-projection/
        int zoom = 9;
        int xTile = 465;
        int yTile = 312;
        Grid grid = new Grid(256 * xTile, 256 * yTile, 256, 256, zoom);
        ReferencedEnvelope envelope = grid.getWebMercatorExtents().getMercatorEnvelopeMeters();
        assertEquals(16358747.0, envelope.getMinX(), 1);
        assertEquals(-4461476.0, envelope.getMinY(), 1);
        assertEquals(16437019.0, envelope.getMaxX(), 1);
        assertEquals(-4383205.0, envelope.getMaxY(), 1);

        // The tile east of Greenwich
        // Reference coordinates in meters from http://www.maptiler.org/google-maps-coordinates-tile-bounds-projection/
        // Expect the zero edge to be more exact, others to within one meter.
        zoom = 12;
        xTile = 2048;
        yTile = 1362;
        grid = new Grid(256 * xTile, 256 * yTile, 256, 256, zoom);
        envelope = grid.getWebMercatorExtents().getMercatorEnvelopeMeters();
        assertEquals(0.0, envelope.getMinX(), 0.01);
        assertEquals(6701999.0, envelope.getMinY(), 1);
        assertEquals(9784.0, envelope.getMaxX(), 1);
        assertEquals(6711783.0, envelope.getMaxY(), 1);

//        /**
//         * Uncomment to manually verify that the Mercator projection works properly. Open the resulting file in GIS
//         * and compare with http://www.maptiler.org/google-maps-coordinates-tile-bounds-projection/
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

    /**
     * This tests serialization on a Shapefile specially designed to fail without rounding error diffusion.
     * The polygons in the shapefile are large but contain relatively few opportunities.
     */
    @Test
    public void trickyRasterizationTest () throws Exception {
        File shapefile = new File(Resources.getResource(Grid.class, "pdx-three-overlapping.shp").toURI());
        List<Grid> grids = Grid.fromShapefile(shapefile, 9);
        assertEquals(grids.size(), 2);
        // Although the second attribute of the shapefile is of type integer, the polygons still create fractional
        // opportunities when split across many cells in the grid. Test both grids with rounding tolerance.
        for (Grid grid : grids) {
            oneRoundTrip(grid, true);
        }
    }

    private void serializationTestLoop (Random random, boolean wholeNumbersOnly) throws Exception {
        for (int i = 0; i < N_ITERATIONS; i++) {
            Grid gridA = generateRandomGrid(random, wholeNumbersOnly);
            oneRoundTrip(gridA, !wholeNumbersOnly);
        }
    }

    private void oneRoundTrip (Grid original, boolean tolerateRounding) throws Exception {
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        original.write(byteArrayOutputStream);
        Grid copy = Grid.read(new ByteArrayInputStream(byteArrayOutputStream.toByteArray()));
        assertGridSemanticEquals(original, copy, tolerateRounding);
    }

    private static Grid generateRandomGrid (Random random, boolean wholeNumbersOnly) {
        final int zoom = 9;
        final int worldWidthPixels = 2 << 8 + zoom;

        int west = random.nextInt(worldWidthPixels - MAX_GRID_WIDTH_PIXELS);
        int north = random.nextInt(worldWidthPixels - MAX_GRID_WIDTH_PIXELS);
        int width = random.nextInt(MAX_GRID_WIDTH_PIXELS) + 1;
        int height = random.nextInt(MAX_GRID_WIDTH_PIXELS) + 1;

        Grid grid = new Grid(west, north, width, height, zoom);
        for (int y = 0; y < grid.extents.height; y++) {
            for (int x = 0; x < grid.extents.width; x++) {
                double amount = random.nextDouble() * MAX_AMOUNT;
                if (wholeNumbersOnly) {
                    amount = Math.round(amount);
                }
                grid.grid[x][y] = amount;
            }
        }
        return grid;
    }

    private static void assertGridSemanticEquals(Grid g1, Grid g2, boolean tolerateRounding) {
        // Note that the name field is excluded because it does not survive serialization.
        assertTrue(g1.hasEqualExtents(g2));
        assertArrayEquals(g1.grid, g2.grid, tolerateRounding);
    }

    /**
     * Compare two 2D arrays of doubles with tolerance on each cell, and on the sums of rows and the whole array.
     */
    private static void assertArrayEquals (
            double[][] a,
            double[][] b,
            boolean tolerateRounding
    ) {
        // Each individual cell can be off by 1/2 due to rounding, plus error term of up to 1/2 from previous cell
        double cellTolerance = tolerateRounding ? 1 : 0;
        assertEquals(a.length, b.length);
        for (int i = 0; i < a.length; i++) {
            double[] ai = a[i];
            double[] bi = b[i];
            assertEquals(ai.length, bi.length);
            for (int j = 0; j < ai.length; j++) {
                assertEquals(ai[j], bi[j], cellTolerance);
            }
        }
        if (tolerateRounding) {
            // When cellTolerance == 0, it can be deduced that the sums of the grids are equal.
            // So additional checks are only necessary when cellTolerance > 0.
            // The grid must be transposed to check row sums, its first index is column (x coordinate).
            final int nCols = a.length;
            final int nRows = a[0].length;
            final double pairSumTolerance = 1; // count of two adjacent cells should be off by max of 1
            final double rowSumTolerance = 0.5; // rounding error may "fall off" the end of a row
            final double gridSumTolerance = 0.5 * nRows; // rounding error on each row is independent
            double aSum = 0;
            double bSum = 0;
            for (int y = 0; y < nRows; y++) {
                double aRowSum = 0;
                double bRowSum = 0;
                for (int x = 0; x < nCols; x++) {
                    final double aVal = a[x][y];
                    final double bVal = b[x][y];
                    aRowSum += aVal;
                    bRowSum += bVal;
                    aSum += aVal;
                    bSum += bVal;
                    if (x > 0) {
                        final double aPrevVal = a[x-1][y];
                        final double bPrevVal = b[x-1][y];
                        assertEquals(aPrevVal + aVal, bPrevVal + bVal, pairSumTolerance);
                    }
                }
                assertEquals(aRowSum, bRowSum, rowSumTolerance);
            }
            assertEquals(aSum, bSum, gridSumTolerance);
        }
    }

}
