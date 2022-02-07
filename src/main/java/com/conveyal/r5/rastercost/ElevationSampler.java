package com.conveyal.r5.rastercost;

import com.conveyal.analysis.datasource.DataSourceException;
import com.conveyal.r5.streets.EdgeStore;
import gnu.trove.list.array.TShortArrayList;
import org.apache.commons.math3.util.FastMath;
import org.geotools.coverage.grid.GridCoverage2D;
import org.geotools.geometry.DirectPosition2D;
import org.geotools.geometry.Envelope2D;
import org.geotools.geometry.jts.ReferencedEnvelope;
import org.geotools.referencing.CRS;
import org.geotools.referencing.crs.DefaultGeographicCRS;
import org.opengis.geometry.DirectPosition;
import org.opengis.referencing.FactoryException;
import org.opengis.referencing.crs.CoordinateReferenceSystem;
import org.opengis.referencing.operation.MathTransform;
import org.opengis.referencing.operation.TransformException;

import java.awt.geom.Point2D;

import static com.conveyal.gtfs.util.Util.METERS_PER_DEGREE_LATITUDE;
import static com.conveyal.r5.streets.VertexStore.fixedDegreesToFloating;

/**
 * A stateful elevation sampler. After creating it, feed it the vertices of a linestring one by one and it will
 * accumulate samples. Intended to be called with com.conveyal.r5.streets.EdgeStore.Edge.forEachPoint().
 */
class ElevationSampler implements EdgeStore.PointConsumer {

    TShortArrayList elevations;

    private final double sampleSpacingMeters;
    private boolean awaitingFirstPoint;
    private double cosLat;
    private double prevLon;
    private double prevLat;
    private double metersToNextPoint;

    private final GridCoverage2D coverage;
    private final Envelope2D coverageWorldEnvelope;

    // Added to the floating point latitude to move each point a fixed distance
    // This could probably be done somehow by composing MathTransforms, or by just preprocessing the inputs
    // though it gets messy where the source and target grid systems are not axis-aligned.
    // Shift street points 3 meters south to see which tree would cast a shadow on them.
    // FIXME this must be configurable and disabled for elevation.
    private final double latShift = -3.0 / METERS_PER_DEGREE_LATITUDE;

    // This is where a reusable RasterDataSource would be handy.
    // TODO consider disabling this on geographic coordinates if it causes any slowdown.
    private final MathTransform wgsToCoverage;

    public ElevationSampler (GridCoverage2D coverage, double sampleSpacingMeters) {
        this.coverage = coverage;
        this.sampleSpacingMeters = sampleSpacingMeters;
        this.coverageWorldEnvelope = coverage.getEnvelope2D();
        reset();
        // Set CRS transform from WGS84 to coverage, if any.
        CoordinateReferenceSystem coverageCrs = coverage.getCoordinateReferenceSystem2D();
        try {
            wgsToCoverage = CRS.findMathTransform(DefaultGeographicCRS.WGS84, coverageCrs);
        } catch (FactoryException e) {
            throw new DataSourceException("Could not create coordinate transform from WGS84.");
        }
    }

    /**
     * Allows the elevation sampler object to be reused, to slightly reduce garbage collection overhead.
     * A fresh elevation sample list is instantiated at each reset, so the old one may be retained by caller.
     */
    public void reset () {
        elevations = new TShortArrayList();
        awaitingFirstPoint = true;
        // Do not sample elevation at the first point provided (the start vertex).
        metersToNextPoint = sampleSpacingMeters;
    }

    @Override
    public void consumePoint (int index, int fixedLat, int fixedLon) {
        // Convert the fixed point coordinate to floating point and ignore the point index number.
        consumePoint(fixedDegreesToFloating(fixedLon), fixedDegreesToFloating(fixedLat));
    }

    /**
     * Internal method accepting linestring points as double precision floating-point values. This matches the
     * coordinates expected by the elevation raster. Alternatively we could change the raster to fixed-point.
     */
    private void consumePoint (double lon, double lat) {
        if (awaitingFirstPoint) {
            cosLat = FastMath.cos(FastMath.toRadians(lat));
            awaitingFirstPoint = false;
        } else {
            consumeSubsequentPoint(lon, lat);
        }
        prevLon = lon;
        prevLat = lat;
    }

    /**
     * This is called only after the first point in a linestring has been consumed. Subsequent points each imply a
     * line segment (technically a great circle arc) between the previous and current point. Points are found every
     * M meters along these line segments and their altitude is evaluated on the supplied raster (coverage) which
     * should use WGS84 geographic coordinates. All geometry operations are flat Cartesian using a crude local
     * spherical projection. We don't need the precision of calculations on the ellipsoid, which are very slow.
     * Often for distance calculations we avoid taking square roots, which are slow. That works because the square
     * root function preserves order when comparing distances. But here I don't think the square root is avoidable,
     * as the proportions we need cannot be derived from the ratio of two square roots.
     */
    private void consumeSubsequentPoint (double lon, double lat) {

        // Deltas in angular degrees. On the ground distance units are different in x and y except at the equator.
        double dx = (lon - prevLon);
        double dy = (lat - prevLat);

        // Find the length of this segment in equator-degrees and convert to meters.
        double dxEquator = cosLat * dx;
        double lengthMeters = FastMath.sqrt(dxEquator * dxEquator + dy * dy) * METERS_PER_DEGREE_LATITUDE;

        // The coordinates of the point we want to sample. Will be advanced along the linestring segments.
        double currLon = prevLon;
        double currLat = prevLat;

        // Find intermediate points along the current linestring segment.
        // This should handle both the case where a point falls inside this segment and the case where it does not.
        double remainingLengthMeters = lengthMeters;
        while (remainingLengthMeters >= metersToNextPoint) {
            double stepFrac = metersToNextPoint / lengthMeters;
            currLon += dx * stepFrac;
            currLat += dy * stepFrac;
            elevations.add(ElevationLoader.clampToShort((int) readElevation(currLon, currLat)));
            remainingLengthMeters -= metersToNextPoint;
            metersToNextPoint = sampleSpacingMeters;
        }

        metersToNextPoint -= remainingLengthMeters;
    }


    /**
     * Call to non-destructively read one elevation point at a time.
     * Objects are reused in each call to recordElevationSample, so this method is not re-entrant or threadsafe.
     * TODO make static by passing in coordinate transform?
     * TODO make transform conditional on a WGS envelope?
     */
    public double readElevation (double lon, double lat) {
        final Point2D wgsPoint = new Point2D.Double();
        wgsPoint.setLocation(lon, lat + latShift);
        // Maybe should use constructor that sets CRS?
        DirectPosition wgsPos = new DirectPosition2D(wgsPoint);
        DirectPosition coveragePos = new DirectPosition2D();
        try {
            wgsToCoverage.transform(wgsPos, coveragePos);
        } catch (TransformException e) {
            throw new RuntimeException("Exception transforming coordinates.", e);
        }
        final double[] elevation = new double[1];
        if (coverageWorldEnvelope.contains(coveragePos)) {
            coverage.evaluate(coveragePos, elevation);
            return elevation[0];
        } else {
            return 0;
        }
    }

//        /**
//         * Call after all points have been consumed. This will compute a final slope cost for any short remaining
//         piece.
//         * EdgeStore.assignElevation()?
//         */
//        public void done () {
//            int toblerSum = 0;
//            double prevElevation = elevations.get(0);
//            for (int i = 1; i < elevations.size(); i++) {
//                toblerSum += elevations.get(i) - elevations.get(i - 1);
//            }
//            final double remainingFrac = (SAMPLE_SPACING_METERS - metersToNextPoint) / SAMPLE_SPACING_METERS;
//            if (remainingFrac > 0) {
//                double finalElevation = readElevation(prevLon, prevLat);
//                toblerSum += finalElevation * remainingFrac;
//            }
//            double nSegments = (elevations.size() - 1) + remainingFrac;
//            checkState(nSegments > 0, "All street edges should have at least one elevation segment.");
//            toblerAverage = elevations.sum() / nSegments;
//        }

    /**
     * Note that this utility method does not reuse the elevation sampler. This causes more garbage collection but
     * is stateless, making it more suitable for use in parallel streams. The speedup from parallel sampling is large.
     */
    public static short[] profileForEdge (EdgeStore.Edge edge, GridCoverage2D coverage, double sampleSpacingMeters) {
        if (edge.getLengthMm() < sampleSpacingMeters * 1000) {
            return ElevationLoader.EMPTY_SHORT_ARRAY;
        }
        ElevationSampler sampler = new ElevationSampler(coverage, sampleSpacingMeters);
        edge.forEachPoint(sampler);
        return sampler.elevations.toArray();
    }

}
