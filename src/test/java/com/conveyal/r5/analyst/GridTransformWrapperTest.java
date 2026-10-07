package com.conveyal.r5.analyst;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test the class that aligns grids of different dimensions at the same zoom level, unifying their 1D indexes.
 */
class GridTransformWrapperTest {

    @Test
    void testTwoAdjacentGrids () {

        // Two grids side by side, right one bigger than than the left, with top 20 pixels lower
        Grid leftGrid = new Grid(2000, 1000, 200, 300, 10);
        Grid rightGrid = new Grid(2200, 1020, 300, 400, 10);

        // One minimum bounding grid exactly encompassing the other two.
        Grid superGrid = new Grid(2000, 1000, 500, 400, 10);

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

    /// Stack two grids of different heights as we do in regional analyses, wrapping them to match
    /// the minimum bounding extents found with forPointsets. This detects a bug where
    /// WebMercatorExtents.expandToInclude truncated the height of the grid, zeroing out
    /// opportunities in the southern rows of taller grids.
    @Test
    void testUnequalHeights () {

        // The vertically short grid is the same width as the tall grid,
        // and overlaps the northeast part of the tall grid.
        Grid tallGrid = new Grid(2000, 1000, 100, 300, 10);
        Grid shortGrid = new Grid(2050, 1100, 100, 50, 10);

        // Put one full-height column and one full-width row of opportunities in each grid.
        // This should catch truncation of the combined grid in either dimension.
        List<Grid.PixelWeight> tallWeights = new ArrayList<>();
        for (int y = 0; y < 300; y++) {
            tallWeights.add(new Grid.PixelWeight(50, y, 1));
        }
        for (int x = 0; x < 100; x++) {
            tallWeights.add(new Grid.PixelWeight(x, 150, 1));
        }
        List<Grid.PixelWeight> shortWeights = new ArrayList<>();
        for (int y = 0; y < 50; y++) {
            shortWeights.add(new Grid.PixelWeight(50, y, 1));
        }
        for (int x = 0; x < 100; x++) {
            shortWeights.add(new Grid.PixelWeight(x, 25, 1));
        }
        tallGrid.incrementFromPixelWeights(tallWeights, 1);
        shortGrid.incrementFromPixelWeights(shortWeights, 1);

        WebMercatorExtents unionExtents = WebMercatorExtents.forPointsets(new PointSet[] {tallGrid, shortGrid});
        assertEquals(new WebMercatorExtents(2000, 1000, 150, 300, 10), unionExtents,
                "Union extents should be the minimum bounding extents of the two grids.");

        for (Grid grid : List.of(tallGrid, shortGrid)) {
            GridTransformWrapper wrapper = new GridTransformWrapper(unionExtents, grid);
            assertEquals(grid.sumTotalOpportunities(), wrapper.sumTotalOpportunities(),
                    "Wrapping a grid to the union extents of a stack should preserve all its opportunities.");
        }
    }

    /*
     * TODO lat/lon based testing
     * Given a set of points at latitudes and longitudes, write the same points into overlapping grids of different
     * dimensions. Then transform all grids into a single super-grid, and make sure the opportunity counts at different
     * lat/lon points are identical in the subgrids and the transformed (wrapped) ones.
     */


}
