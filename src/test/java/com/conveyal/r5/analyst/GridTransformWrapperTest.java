package com.conveyal.r5.analyst;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test the class that aligns grids of different dimensions at the same zoom level, unifying their 1D indexes.
 */
class GridTransformWrapperTest {

    @Test
    void testTwoAdjacentGrids () {

        // Two grids side by side, right one bigger than than the left, with top 20 pixels lower
        Grid leftGrid = new Grid(10, 200, 300, 1000, 2000);
        Grid rightGrid = new Grid(10, 300, 400, 1020, 2200);

        // One minimum bounding grid exactly encompassing the other two.
        Grid superGrid = new Grid(10, 500, 400, 1000, 2000);

        // Make a column of pixel weights 2 pixels wide and 26 pixels high.
        List<Grid.PixelWeight> weights = new ArrayList<>();
        for (int x = 10; x < 12; x++) {
            for (int y = 22; y < 48; y++) {
                weights.add(new Grid.PixelWeight(x, y, x+y));
            }
        }

        // Translate the pixel weights relative to the right-hand grid into supergrid x and y coordinates.
        List<Grid.PixelWeight> rightTranslatedWeights = weights.stream()
                .map(pw -> new Grid.PixelWeight(pw.x + 200, pw.y + 20, pw.weight))
                .collect(Collectors.toList());

        // Burn the pixel weights into all the grids.
        // Weights are doubled in the right-hand grid.
        // The supergrid should be a merge of the two subgrids.
        leftGrid.incrementFromPixelWeights(weights, 1);
        rightGrid.incrementFromPixelWeights(weights, 2);
        superGrid.incrementFromPixelWeights(weights, 1);
        superGrid.incrementFromPixelWeights(rightTranslatedWeights, 2);

        // Make some extents even bigger than the merged supergrid
        WebMercatorExtents superSuperExtents = new WebMercatorExtents(1900, 950, 600, 500, 10);

        GridTransformWrapper leftWrapper = new GridTransformWrapper(superSuperExtents, leftGrid);
        GridTransformWrapper rightWrapper = new GridTransformWrapper(superSuperExtents, rightGrid);
        GridTransformWrapper superWrapper = new GridTransformWrapper(superSuperExtents, superGrid);

        double totalWeight = 0;
        final int superSuperCellCount = superSuperExtents.width * superSuperExtents.height;
        for (int i = 0; i < superSuperCellCount; i++) {
            double superCount = superWrapper.getOpportunityCount(i);
            double leftCount = leftWrapper.getOpportunityCount(i);
            double rightCount = rightWrapper.getOpportunityCount(i);
            double mergedCount = leftCount + rightCount;
            totalWeight += mergedCount;
            assertEquals(superCount, mergedCount, "Supergrid should contain the sum of left and right");
        }
        assertEquals(weights.stream().mapToDouble(pw -> pw.weight).sum() * 3, totalWeight);
    }

    /**
     * Ensure that we refuse to create a transform wrapper that attempts to transform across zoom levels.
     */
    @Test
    void testMismatchedZoomLevels () {
        Grid grid = new Grid(10, 10, 10, 10, 10);
        WebMercatorExtents webMercatorExtents = new WebMercatorExtents(10, 10, 10, 10, 11);
        assertThrows(IllegalArgumentException.class, () -> new GridTransformWrapper(webMercatorExtents, grid));
    }

    /*
     * TODO lat/lon based testing
     * Given a set of points at latitudes and longitudes, write the same points into overlapping grids of different
     * dimensions. Then transform all grids into a single super-grid, and make sure the opportunity counts at different
     * lat/lon points are identical in the subgrids and the transformed (wrapped) ones.
     */


}
