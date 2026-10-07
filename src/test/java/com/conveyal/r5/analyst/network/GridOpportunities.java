package com.conveyal.r5.analyst.network;

import com.conveyal.r5.analyst.FreeFormPointSet;
import com.conveyal.r5.analyst.Grid;
import com.conveyal.r5.analyst.WebMercatorExtents;
import org.locationtech.jts.geom.Coordinate;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/// Creates freeform opportunity PointSets over the intersections of a GridLayout for use in accessibility tests.
///
/// The point at each intersection has a pseudorandom opportunity count, drawn from a generator with a fixed seed so
/// the table is identical on every run. Access to opportunities results are sums of the counts over reachable cells,
/// and random weights make that sum a fingerprint of which cells were reached and counted. The probability of two
/// different sets of cells producing equal sums is around one in MAX_WEIGHT, regardless of how the wrong set arises.
///
/// These weights intentionally bear no algebraic relationship to cell position. Weights computed from position may
/// produce collisions when indexing is accidentally mirrored or transposed. This does not require the transposition
/// to be symmetric cell for cell, only for the reachable cell coordinates to have balanced moment about the axis. Our
/// test networks are deliberately nearly symmetric, making accidentally balanced reachability patterns more plausible
/// than they would otherwise be in a real-world network. Even if such patterns are avoided by construction, later edits
/// to test scenes (e.g. recentered origin or symmetric route layout) could silently raise the risk of collisions.
///
/// No set of weights can detect a bug that sums exactly the same set of cells it should have (e.g. erroneously
/// mirrored reads from a region that is itself symmetric about the axis) because that sum is numerically correct.
public class GridOpportunities {

    /// Arbitrary fixed seed, ensuring expected totals are stable across runs and branches.
    private static final long WEIGHT_TABLE_SEED = 20260725;

    /// Weights are drawn from [1, MAX_WEIGHT]. The maximum weight is constrained by two things. It should be large,
    /// because the chance of two different cell sets having the same sum is about 1/MAX_WEIGHT. But accessibility
    /// values are reported as ints and the largest possible value is the sum of every cell in the table. For a layout
    /// with n intersections we need MAX_WEIGHT < 2^31 / n. These tests use 41x41 grids, so the maximum workable value
    /// is  2^31 / (41 * 41) = 1,277,503 which leaves some headroom above this constant.
    private static final int MAX_WEIGHT = 1_000_000;

    /// Produce a stable weight table for a layout with one pseudorandom weight per intersection in row-major order.
    /// The weights are independent draws so the table may contain duplicate values, which is not really a problem.
    /// The collision resistance of the sums comes from independence of draws, not from uniqueness of values.
    private static int[] weightTable (GridLayout gridLayout) {
        int intersectionsAcross = gridLayout.widthAndHeightInBlocks + 1;
        int nWeights = intersectionsAcross * intersectionsAcross;
        Random random = new Random(WEIGHT_TABLE_SEED);
        int[] table = new int[nWeights];
        for (int i = 0; i < nWeights; i++) {
            table[i] = 1 + random.nextInt(MAX_WEIGHT);
        }
        return table;
    }

    /// Produce a FreeFormPointSet for the given layout with one point per intersection in the given inclusive range of
    /// grid coordinates, each with its opportunity count drawn from the stable weight table for the entire grid.
    public static FreeFormPointSet freeformPointSet (GridLayout gridLayout, int minX, int minY, int maxX, int maxY) {
        int[] table = weightTable(gridLayout);
        int intersectionsAcross = gridLayout.widthAndHeightInBlocks + 1;
        List<Coordinate> coordinates = new ArrayList<>();
        List<Double> counts = new ArrayList<>();
        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                coordinates.add(gridLayout.getIntersectionLatLon(x, y));
                counts.add((double) table[y * intersectionsAcross + x]);
            }
        }
        return new FreeFormPointSet(
                coordinates.toArray(new Coordinate[0]),
                counts.stream().mapToDouble(Double::doubleValue).toArray()
        );
    }

    /// Produce a FreeFormPointSet with one point for every intersection in the layout, each with an
    /// opportunity count from the stable weight table for the entire grid.
    public static FreeFormPointSet freeformPointSet (GridLayout gridLayout) {
        int max = gridLayout.widthAndHeightInBlocks;
        return freeformPointSet(gridLayout, 0, 0, max, max);
    }

    /// Deposit the points of the given PointSet into a new Grid with the given extents. Points falling outside the
    /// extents are dropped, which lets tests deliberately construct clipped opportunity grids.
    public static Grid makeGrid (WebMercatorExtents extents, FreeFormPointSet points) {
        Grid grid = new Grid(extents);
        for (int p = 0; p < points.featureCount(); p++) {
            int x = Grid.lonToPixel(points.getLon(p), extents.zoom) - extents.west;
            int y = Grid.latToPixel(points.getLat(p), extents.zoom) - extents.north;
            if (x >= 0 && x < extents.width && y >= 0 && y < extents.height) {
                grid.grid[x][y] += points.getOpportunityCount(p);
            }
        }
        return grid;
    }

    /// Compute the sum of all opportunity counts in the given Grid. This serves as a pre-check that
    /// no opportunity was dropped when writing a PointSet into extents meant to contain all of it.
    public static double totalOpportunities (Grid grid) {
        double total = 0;
        for (double[] column : grid.grid) {
            for (double cell : column) {
                total += cell;
            }
        }
        return total;
    }

}
