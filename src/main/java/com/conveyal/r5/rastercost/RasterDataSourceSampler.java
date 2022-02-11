package com.conveyal.r5.rastercost;

import com.conveyal.analysis.components.WorkerComponents;
import com.conveyal.analysis.datasource.DataSourceException;
import com.conveyal.file.FileStorageKey;
import com.conveyal.r5.streets.EdgeStore;
import gnu.trove.list.TDoubleList;
import gnu.trove.list.array.TDoubleArrayList;
import org.apache.commons.math3.util.FastMath;
import org.geotools.coverage.grid.GridCoverage2D;
import org.geotools.coverage.grid.Interpolator2D;
import org.geotools.coverage.grid.io.AbstractGridFormat;
import org.geotools.coverage.grid.io.GridCoverage2DReader;
import org.geotools.coverage.grid.io.GridFormatFinder;
import org.geotools.geometry.DirectPosition2D;
import org.geotools.geometry.Envelope2D;
import org.geotools.referencing.CRS;
import org.geotools.referencing.crs.DefaultGeographicCRS;
import org.geotools.util.factory.Hints;
import org.opengis.geometry.DirectPosition;
import org.opengis.referencing.crs.CoordinateReferenceSystem;
import org.opengis.referencing.operation.MathTransform;
import org.opengis.referencing.operation.TransformException;

import javax.media.jai.InterpolationBilinear;
import java.awt.geom.Point2D;
import java.io.File;

import static com.conveyal.file.FileCategory.DATASOURCES;
import static com.conveyal.file.FileStorageFormat.GEOTIFF;
import static com.conveyal.gtfs.util.Util.METERS_PER_DEGREE_LATITUDE;
import static com.conveyal.r5.streets.VertexStore.fixedDegreesToFloating;
import static com.google.common.base.Preconditions.checkArgument;

/**
 * An attempt to generalize reading from a raster DataSource.
 * The usual case is that we sample along edges and store those sample points, or sample individual points.
 * We have a class that performs the sampling but it was originally designed for elevation data.
 * The trick is that we may load from different data types (single-bit integers through double precision floating point).
 * We also want to perform the sampling in a multi-threaded way, so the sampler instance needs to expose methods that
 * are threadsafe when called in a parallel stream or thread pool. But all those millions of edge-sampling operations
 * will use the same configuration so we want to set all that here rather than on separate objects generated at each
 * edge-sampling. But certain state needs to be retained on each edge, which implies a class instance (inner?) per
 * edge.
 *
 */
public class RasterDataSourceSampler {

    private final String dataSourceId;
    private final double sampleSpacingMeters;
    private final boolean interpolate;

    /** The GeoTools coverage object we're going to read from. */
    private final GridCoverage2D coverage;

    private double latShiftDegrees = 0;

    /**
     * The transform from WGS84 geographic coordinates to whatever system is used by the grid coverage.
     * TODO consider disabling this on geographic coordinates if it causes any slowdown. Does GeoTools have a noop coverage?
     */
    private final MathTransform wgsToCoverage;

    /**
     * The absolute envelope of the coverage in its own CRS.
     * This is not the relative envelope of the grid of pixels, and is not necessarily in geographic coordinates.
     * @param interpolate whether to interpolate the raster. This takes most of the time during elevation loading.
     */
    private final Envelope2D coverageWorldEnvelope;

    public RasterDataSourceSampler (String dataSourceId, double sampleSpacingMeters, boolean interpolate) {
        this.dataSourceId = dataSourceId;
        this.sampleSpacingMeters = sampleSpacingMeters;
        this.interpolate = interpolate;
        try {
            FileStorageKey fileStorageKey = new FileStorageKey(DATASOURCES, dataSourceId, GEOTIFF.extension);
            File localRasterFile = WorkerComponents.fileStorage.getFile(fileStorageKey);
            AbstractGridFormat format = GridFormatFinder.findFormat(localRasterFile);
            // Only relevant for certain files with WGS CRS?
            Hints hints = new Hints(Hints.FORCE_LONGITUDE_FIRST_AXIS_ORDER, Boolean.TRUE);
            GridCoverage2DReader coverageReader = format.getReader(localRasterFile, hints);
            GridCoverage2D uninterpolatedCoverage = coverageReader.read(null);
            if (interpolate) {
                // it.geosolutions.jaiext.interpolators.InterpolationBilinear seems to be able to handle nodata values.
                // javax.media.jai.InterpolationBilinear apparently cannot handle nodata.
                // Can interpolation instead be achieved with Hints.VALUE_INTERPOLATION_BICUBIC on the original raster?
                coverage = Interpolator2D.create(uninterpolatedCoverage, new InterpolationBilinear());
            } else {
                coverage = uninterpolatedCoverage;
            }
            this.coverageWorldEnvelope = coverage.getEnvelope2D();
            // Set CRS transform from WGS84 to coverage, if any.
            CoordinateReferenceSystem coverageCrs = coverage.getCoordinateReferenceSystem2D();
            wgsToCoverage = CRS.findMathTransform(DefaultGeographicCRS.WGS84, coverageCrs);
        } catch (Exception ex) {
            throw new DataSourceException("Failed to open raster data source with id: " + dataSourceId, ex);
        }
    }

    /**
     * Shift the raster the specified number of meters north on the fly.
     * This is actually accomplished by moving each sample point the equivalent number of degrees south.
     */
    public void setNorthShiftMeters (double northShiftMeters) {
        checkArgument(northShiftMeters > -20 && northShiftMeters < 20, "northShiftMeters should be in range (-20...20).");
        // this.northShiftMeters = northShiftMeters;
        this.latShiftDegrees = northShiftMeters / METERS_PER_DEGREE_LATITUDE;
    }

    /**
     * Call to non-destructively read one elevation point at a time.
     * Objects are created on each call to recordElevationSample so this should be threadsafe.
     */
    public double readElevation (double lon, double lat) {
        // TODO make transform conditional on presence of a WGS envelope? Create DirectPosition directly, not Point2D.
        final Point2D wgsPoint = new Point2D.Double();
        wgsPoint.setLocation(lon, lat - latShiftDegrees);
        // Maybe should use constructor that sets CRS?
        DirectPosition wgsPos = new DirectPosition2D(wgsPoint);
        DirectPosition coveragePos = new DirectPosition2D();
        try {
            wgsToCoverage.transform(wgsPos, coveragePos);
        } catch (TransformException e) {
            throw new RuntimeException("Exception transforming coordinates.", e);
        }
        // Weirdly, GeoTools can only save the values into an array.
        final double[] elevation = new double[1];
        // Catch points outside the coverage, which would otherwise throw an exception, which is slow to handle.
        if (coverageWorldEnvelope.contains(coveragePos)) {
            coverage.evaluate(coveragePos, elevation);
            return elevation[0];
        } else {
            return 0;
        }
    }

    public static final double[] EMPTY_DOUBLE_ARRAY = new double[0];

    /**
     * Note that this utility method does not reuse the elevation sampler. This causes more garbage collection but
     * is stateless, making it more suitable for use in parallel streams. The speedup from parallel sampling is large.
     */
    public double[] sampleEdge (EdgeStore.Edge edge) {
        if (edge.getLengthMm() < sampleSpacingMeters * 1000) {
            return EMPTY_DOUBLE_ARRAY;
        }
        EdgeSampler sampler = new EdgeSampler();
        edge.forEachPoint(sampler);
        return sampler.samples.toArray();
    }

    // Only progress reporting prevents us from calling this.
    // It is imaginable to supply the edgeStore to the RasterDataSourceSampler (or to a composed object)
    // and move all the iteration logic in here.
    public double[] sampleEdgePair (int edgePairIndex, EdgeStore edgeStore) {
        return sampleEdge(edgeStore.getCursor(edgePairIndex * 2));
    }

    /**
     * Inner _non-static_ class for statefully sampling the coverage at evenly spaced points along a single edge.
     * We create one of these per edge sampled. Previously the instances were made reusable to avoid small object
     * creation and garbage collection, but JVM garbage collection is now optimized for .
     * In addition, the speedup and simplification from sampling edges in parallel streams seems to far outweigh
     * the cost of additional object churn, at least for certain rasters (uncompressed or tiled ones?).
     */
    private class EdgeSampler implements EdgeStore.PointConsumer {

        // Samples along the edge are accumulated here.
        private TDoubleList samples = new TDoubleArrayList();
        private boolean awaitingFirstPoint = true;
        private double cosLat;
        private double prevLon;
        private double prevLat;
        private double metersToNextPoint = sampleSpacingMeters;

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
                // This could probably be set once for the entire coverage.
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
                // This could possibly be factored out and precomputed only once.
                double stepFrac = metersToNextPoint / lengthMeters;
                currLon += dx * stepFrac;
                currLat += dy * stepFrac;
                // Previously: ElevationLoader.clampToShort((int) ...)
                samples.add(readElevation(currLon, currLat));
                remainingLengthMeters -= metersToNextPoint;
                metersToNextPoint = sampleSpacingMeters;
            }
            metersToNextPoint -= remainingLengthMeters;
        }
    }

}
