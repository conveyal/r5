package com.conveyal.r5.analyst.network;

import com.conveyal.r5.OneOriginResult;
import com.conveyal.r5.analyst.FreeFormPointSet;
import com.conveyal.r5.analyst.Grid;
import com.conveyal.r5.analyst.TravelTimeComputer;
import com.conveyal.r5.analyst.WebMercatorExtents;
import com.conveyal.r5.analyst.cluster.AnalysisWorkerTask;
import com.conveyal.r5.transit.TransportNetwork;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.conveyal.r5.analyst.WebMercatorExtents.DEFAULT_ZOOM;
import static com.conveyal.r5.analyst.network.GridOpportunities.makeGrid;
import static com.conveyal.r5.analyst.network.GridOpportunities.totalOpportunities;
import static com.conveyal.r5.analyst.network.SimpsonDesertTests.SIMPSON_DESERT_CORNER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests that accessibility results are unaffected by destination opportunity grids being wrapped
/// or stacked to match the travel time grid.
///
/// All assertions here check identity or conservation, rather than checking against theoretically
/// predicted results. In identity tests, we compare two ways of placing the same opportunities in
/// the same cells and make sure their access sums are identical. In the conservation check we just
/// make sure that when the whole grid should be reachable, the indicator value equals the sum of
/// all opportunities in the grid.
///
/// This network uses only scheduled routes, making results fully deterministic.
/// Every intersection has a point with a pseudorandom number of opportunities, revealing changes.
public class DestinationGridLayeringTests {

    private static GridLayout gridLayout;
    private static TransportNetwork network;
    private static WebMercatorExtents alignedExtents;
    private static FreeFormPointSet allPoints;

    @BeforeAll
    static void buildNetwork () {
        gridLayout = new GridLayout(SIMPSON_DESERT_CORNER, 40);
        // Scheduled routes crossing the grid every ten blocks keep every pixel within walking reach
        // of a stop. There are no frequency routes, as they would make results nondeterministic.
        for (int i = 5; i <= 35; i += 10) {
            gridLayout.addHorizontalRoute(i, 10);
            gridLayout.addVerticalRoute(i, 10);
        }
        network = gridLayout.generateNetwork();
        alignedExtents = WebMercatorExtents.forWgsEnvelope(gridLayout.gridEnvelope(), DEFAULT_ZOOM);
        allPoints = GridOpportunities.freeformPointSet(gridLayout);
    }

    private static GridTaskBuilder taskBuilder () {
        return gridLayout.newTaskBuilder()
                .weekdayMorningPeak()
                .setOrigin(15, 15)
                .recordAccessibility();
    }

    /// Like taskBuilder, but with the reduced cutoff set that regional accessibility tasks require.
    /// Single point tasks require the full 121 cutoffs, regional tasks allow at most 12.
    private static GridTaskBuilder regionalTaskBuilder () {
        return taskBuilder().cutoffsMinutes(0, 15, 30, 45, 60, 75, 90, 105, 120);
    }

    private static int[][][] accessibility (AnalysisWorkerTask task) {
        OneOriginResult result = new TravelTimeComputer(task, network).computeTravelTimes();
        return result.accessibility.getIntValues();
    }

    /// Assert that two accessibility results are identical for every point set, percentile, and cutoff.
    private static void assertSameAccessibility (int[][][] expected, int[][][] actual, int expectedPointSet,
                                                 int actualPointSet) {
        for (int p = 0; p < expected[expectedPointSet].length; p++) {
            for (int c = 0; c < expected[expectedPointSet][p].length; c++) {
                assertEquals(expected[expectedPointSet][p][c], actual[actualPointSet][p][c],
                        String.format("Accessibility differs at percentile index %d, cutoff index %d.", p, c));
            }
        }
    }

    /// Ensure results include a cutoff where some but not all opportunities are reachable,
    /// so that a misplacement of opportunities would actually change the values compared.
    private static void assertNonTrivial (int[][][] accessibility, double grandTotal) {
        boolean partial = false;
        int[] cutoffs = accessibility[0][0];
        for (int value : cutoffs) {
            if (value > 0 && value < Math.round(grandTotal)) {
                partial = true;
                break;
            }
        }
        assertTrue(partial, "Expected some cutoff to reach some but not all opportunities.");
    }

    /// Verify that identical accessibility is produced from the same set of opportunities expressed two different
    /// ways: once in a grid exactly matching the single-point task's extents, and once embedded in a larger grid offset
    /// asymmetrically in all four directions. The latter involves GridTransformWrapper, and asymmetric offsets ensure
    /// a mirrored or transposed index mapping does not slip through by symmetry.
    @Test
    public void offsetEmbedding () {
        Grid aligned = makeGrid(alignedExtents, allPoints);
        WebMercatorExtents offsetExtents = new WebMercatorExtents(
                alignedExtents.west - 3, alignedExtents.north - 2,
                alignedExtents.width + 8, alignedExtents.height + 5, alignedExtents.zoom);
        Grid embedded = makeGrid(offsetExtents, allPoints);
        assertEquals(totalOpportunities(aligned), totalOpportunities(embedded),
                "Both grids should contain every opportunity point.");

        int[][][] accAligned = accessibility(taskBuilder().griddedDestinations(aligned).buildSinglePoint());
        int[][][] accEmbedded = accessibility(taskBuilder().griddedDestinations(embedded).buildSinglePoint());
        assertNonTrivial(accAligned, allPoints.sumTotalOpportunities());
        assertSameAccessibility(accAligned, accEmbedded, 0, 0);
    }

    /// The same subset of opportunities, once burned into a grid matching the task extents and once into a smaller
    /// grid covering only the southwest quadrant, must yield identical accessibility. The smaller grid makes the
    /// wrapper handle cells within the task's extents that lie entirely outside the opportunity grid.
    @Test
    public void clippedEmbedding () {
        FreeFormPointSet quadrant = GridOpportunities.freeformPointSet(gridLayout, 0, 0, 20, 20);
        Grid alignedSubset = makeGrid(alignedExtents, quadrant);
        // Extents covering just the southwest quadrant of the desert, with a margin of two pixels. Grid layout y
        // increases northward while Mercator pixel y increases southward, so the quadrant is at high pixel y.
        int halfWidth = alignedExtents.width / 2 + 2;
        int halfHeight = alignedExtents.height / 2 + 2;
        WebMercatorExtents quadrantExtents = new WebMercatorExtents(
                alignedExtents.west, alignedExtents.north + (alignedExtents.height - halfHeight),
                halfWidth, halfHeight, alignedExtents.zoom);
        Grid clipped = makeGrid(quadrantExtents, quadrant);
        assertEquals(totalOpportunities(alignedSubset), totalOpportunities(clipped),
                "Both grids should contain every point of the quadrant.");

        int[][][] accAligned = accessibility(taskBuilder().griddedDestinations(alignedSubset).buildSinglePoint());
        int[][][] accClipped = accessibility(taskBuilder().griddedDestinations(clipped).buildSinglePoint());
        assertNonTrivial(accAligned, quadrant.sumTotalOpportunities());
        assertSameAccessibility(accAligned, accClipped, 0, 0);
    }

    /// Two opportunity grids of unequal widths stacked on one regional task must each yield the same accessibility
    /// as they do when used alone. The regional code path derives task extents as the union of the grid extents,
    /// then wraps each grid to that union.
    @Test
    public void stackedUnequalWidths () {
        Grid full = makeGrid(alignedExtents, allPoints);
        WebMercatorExtents narrowExtents = new WebMercatorExtents(
                alignedExtents.west + 2, alignedExtents.north,
                alignedExtents.width - 6, alignedExtents.height, alignedExtents.zoom);
        // Points in the outermost columns of the desert fall outside these extents and are dropped by construction.
        Grid narrow = makeGrid(narrowExtents, allPoints);

        int[][][] stacked = accessibility(regionalTaskBuilder().griddedDestinations(narrow, full).buildRegional());
        int[][][] narrowAlone = accessibility(regionalTaskBuilder().griddedDestinations(narrow).buildRegional());
        int[][][] fullAlone = accessibility(regionalTaskBuilder().griddedDestinations(full).buildRegional());
        assertNonTrivial(fullAlone, allPoints.sumTotalOpportunities());
        assertSameAccessibility(narrowAlone, stacked, 0, 0);
        assertSameAccessibility(fullAlone, stacked, 0, 1);
    }

    /// Like stackedUnequalWidths but with grids of unequal heights.
    @Test
    public void stackedUnequalHeights () {
        Grid full = makeGrid(alignedExtents, allPoints);
        WebMercatorExtents shortExtents = new WebMercatorExtents(
                alignedExtents.west, alignedExtents.north + 1,
                alignedExtents.width, alignedExtents.height - 4, alignedExtents.zoom);
        // Points in the outermost rows of the desert fall outside these extents and are dropped by construction.
        Grid shortGrid = makeGrid(shortExtents, allPoints);

        int[][][] stacked = accessibility(regionalTaskBuilder().griddedDestinations(full, shortGrid).buildRegional());
        int[][][] fullAlone = accessibility(regionalTaskBuilder().griddedDestinations(full).buildRegional());
        int[][][] shortAlone = accessibility(regionalTaskBuilder().griddedDestinations(shortGrid).buildRegional());
        assertNonTrivial(fullAlone, allPoints.sumTotalOpportunities());
        assertSameAccessibility(fullAlone, stacked, 0, 0);
        assertSameAccessibility(shortAlone, stacked, 0, 1);
    }

    /// At a cutoff high enough to ensure every destination pixel is reachable, accessibility should
    /// equal the total total of all opportunity counts. This should hold at every percentile.
    @Test
    public void grandTotalConservation () {
        Grid aligned = makeGrid(alignedExtents, allPoints);
        assertEquals(allPoints.sumTotalOpportunities(), totalOpportunities(aligned),
                "No opportunity points may fall outside the task extents, or the expected total would be wrong.");

        int[][][] acc = accessibility(taskBuilder().griddedDestinations(aligned).buildSinglePoint());
        int grandTotal = (int) Math.round(allPoints.sumTotalOpportunities());
        // Cutoffs on single point tasks are always 0..120 minutes, so indexes correspond to minutes.
        for (int p = 0; p < DistributionTester.PERCENTILES.length; p++) {
            assertEquals(0, acc[0][p][0], "No opportunities should be reachable with a cutoff of zero.");
            assertEquals(grandTotal, acc[0][p][120],
                    "All opportunities should be reachable within the maximum cutoff, at percentile index " + p);
        }
    }

}
