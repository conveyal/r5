package com.conveyal.r5.rastercost;

import com.conveyal.r5.streets.EdgeStore;
import gnu.trove.list.array.TShortArrayList;
import org.apache.commons.math3.util.FastMath;
import org.geotools.coverage.grid.GridCoverage2D;
import org.geotools.geometry.Envelope2D;

import java.awt.geom.Point2D;

import static com.conveyal.gtfs.util.Util.METERS_PER_DEGREE_LATITUDE;
import static com.conveyal.r5.streets.VertexStore.fixedDegreesToFloating;

/**
 * A stateful elevation sampler. After creating it, feed it the vertices of a linestring one by one and it will
 * accumulate samples. Intended to be called with com.conveyal.r5.streets.EdgeStore.Edge.forEachPoint().
 */
class ElevationSampler implements EdgeStore.PointConsumer {

    TShortArrayList elevations;

    private boolean awaitingFirstPoint;
    private double cosLat;
    private double prevLon;
    private double prevLat;
    private double metersToNextPoint;

    private final GridCoverage2D coverage;
    private final Envelope2D coverageWorldEnvelope;

    public ElevationSampler (GridCoverage2D coverage) {
        this.coverage = coverage;
        this.coverageWorldEnvelope = coverage.getEnvelope2D();
        reset();
    }

    /**
     * Allows the elevation sampler object to be reused, to slightly reduce garbage collection overhead.
     * A fresh elevation sample list is instantiated at each reset, so the old one may be retained by caller.
     */
    public void reset () {
        elevations = new TShortArrayList();
        awaitingFirstPoint = true;
        // Do not sample elevation at the first point provided (the start vertex).
        metersToNextPoint = ElevationLoader.ELEVATION_SAMPLE_SPACING_METERS;
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
            metersToNextPoint = ElevationLoader.ELEVATION_SAMPLE_SPACING_METERS;
        }

        metersToNextPoint -= remainingLengthMeters;
    }

    // Objects reused in each call to recordElevationSample (so this class is not threadsafe).
    // We may want to just make readElevation static and make these locals on the stack.
    private final Point2D pointToRead = new Point2D.Double();
    private final double[] elevation = new double[1];

    /**
     * Call to non-destructively read one elevation point at a time.
     * Objects are reused in each call to recordElevationSample, so this method is not re-entrant or threadsafe.
     */
    public double readElevation (double lon, double lat) {
        pointToRead.setLocation(lon, lat);
        if (coverageWorldEnvelope.contains(pointToRead)) {
            coverage.evaluate(pointToRead, elevation);
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
    public static short[] profileForEdge (EdgeStore.Edge edge, GridCoverage2D coverage) {
        if (edge.getLengthMm() < ElevationLoader.ELEVATION_SAMPLE_SPACING_METERS * 1000) {
            return ElevationLoader.EMPTY_SHORT_ARRAY;
        }
        ElevationSampler sampler = new ElevationSampler(coverage);
        edge.forEachPoint(sampler);
        return sampler.elevations.toArray();
    }

}
