package com.conveyal.r5.analyst;

import org.locationtech.jts.geom.Envelope;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;

/**
 * This wraps a gridded destination pointset (the "source"), remapping its point indexes to match those of another grid.
 * This can be used to stack pointsets of varying dimensions, or to calculate accessibility to pointsets of
 * different dimensions than a travel time surface grid.
 *
 * These wrappers should not be used for linking as they don't have a BasePointSet. Another WebMercatorGridPointSet will
 * be linked, and these will just serve as opportunity grids of identical dimensions during accessibility calculations.
 */
public class GridTransformWrapper extends PointSet {

    /** Defer to this PointSet for everything but opportunity counts, including grid dimensions and lat/lon. */
    private final WebMercatorGridPointSet targetGrid;

    /** Defer to this PointSet to get opportunity counts (transforming indexes to those of targetPointSet). */
    private final Grid sourceGrid;

    /** The difference in zoom levels between the source and target grids. Constrained positive (only upsample). */
    private final int dz;

    /**
     * The fraction of opportunities in a sourceGrid cell that are allocated to each targetGrid cell it contains.
     * We uniformly distribute opportunities when upsampling, so by the definition of map tile zoom levels this should
     * always be the inverse of a power of two, in the range (0...1]. Total opportunities should be exactly conserved
     * because floating point division by powers of two is exact: in base 2 it just moves the decimal point N places.
     */
    private final double targetCellFraction;

    /**
     * Wraps the sourceGrid such that the opportunity count is read from the geographic locations of indexes in the
     * targetGrid. The targetGrid may be at a higher zoom level than the source grid, in which case opportunities will
     * be distributed equally among the higher-resolution cells. Any opportunities outside the targetGrid cannot be
     * indexed so are effectively zero for the purpose of accessibility calculations.
     */
    public GridTransformWrapper (WebMercatorExtents targetGridExtents, Grid sourceGrid) {
        checkArgument(targetGridExtents.zoom >= sourceGrid.zoom, "We only upsample grids, not downsample them.");
        // Make a pointset for these extents so we can defer to its methods for lat/lon lookup, size, etc.
        this.targetGrid = new WebMercatorGridPointSet(targetGridExtents);
        this.sourceGrid = sourceGrid;
        dz = targetGrid.zoom - sourceGrid.zoom;
        targetCellFraction = 1.0 / (1 << (dz * 2));
        checkState(dz >= 0 && dz <= 3, "Difference in zoom levels out of acceptable range: " + dz);
        checkState(targetCellFraction > 0 && targetCellFraction <= 1, "Opportunity scaling factor out of range.");
    }

    // Given an index in the targetGrid, return the corresponding 1D index into the sourceGrid or -1 if the target
    // index is for a point outside the source grid.
    // This could certainly be made more efficient (but complex) by forcing sequential iteration over opportunity counts
    // and disallowing random access, using a new PointSetIterator class that allows reading lat, lon, and counts.
    private int transformIndex (int ti) {
        final int sx = ((ti % targetGrid.width) + targetGrid.west - (sourceGrid.west << dz)) >> dz;
        final int sy = ((ti / targetGrid.width) + targetGrid.north - (sourceGrid.north << dz)) >> dz;
        if (sx < 0 || sx >= sourceGrid.width || sy < 0 || sy >= sourceGrid.height) {
            // Point in target grid lies outside source grid, there is no valid index. Return special value.
            return -1;
        }
        return sy * sourceGrid.width + sx;
    }

    @Override
    public double getLat (int i) {
        return targetGrid.getLat(i);
    }

    @Override
    public double getLon (int i) {
        return targetGrid.getLon(i);
    }

    @Override
    public int featureCount () {
        return targetGrid.featureCount();
    }

    @Override
    public double sumTotalOpportunities() {
        // Very inefficient compared to the other implementations as it does a lot of index math, but it should work.
        double totalOpportunities = 0;
        for (int i = 0; i < featureCount(); i++) {
            totalOpportunities += getOpportunityCount(i);
        }
        return totalOpportunities;
    }

    @Override
    public double getOpportunityCount (int targetIndex) {
        int sourceindex = transformIndex(targetIndex);
        if (sourceindex < 0) {
            return 0;
        } else {
            return sourceGrid.getOpportunityCount(sourceindex) * targetCellFraction;
        }
    }

    @Override
    public Envelope getWgsEnvelope () {
        return targetGrid.getWgsEnvelope();
    }

    @Override
    public WebMercatorExtents getWebMercatorExtents () {
        return targetGrid.getWebMercatorExtents();
    }

}
