package com.conveyal.r5.elevation;

import com.conveyal.osmlib.OSM;
import com.conveyal.r5.point_to_point.builder.TNBuilderConfig;
import com.conveyal.r5.streets.EdgeStore;
import com.conveyal.r5.streets.StreetLayer;
import com.conveyal.r5.streets.VertexStore;
import com.conveyal.r5.util.LambdaCounter;
import gnu.trove.list.array.TFloatArrayList;
import gnu.trove.list.array.TShortArrayList;
import org.apache.commons.math3.stat.descriptive.StatisticalSummary;
import org.apache.commons.math3.util.FastMath;
import org.geotools.coverage.grid.GridCoverage2D;
import org.geotools.coverage.grid.io.AbstractGridFormat;
import org.geotools.coverage.grid.io.GridFormatFinder;
import org.geotools.geometry.Envelope2D;
import org.geotools.util.factory.Hints;
import org.locationtech.jts.geom.Envelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.spi.IIORegistry;
import java.awt.geom.Point2D;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.conveyal.gtfs.util.Util.METERS_PER_DEGREE_LATITUDE;
import static com.conveyal.r5.streets.VertexStore.fixedDegreesToFloating;
import static com.google.common.base.Preconditions.checkState;

/**
 * 
 *
 * 
 *
 * 
 */
public class ElevationLoader {

    private static final Logger LOG = LoggerFactory.getLogger(ElevationLoader.class);

    public static final short[] EMPTY_SHORT_ARRAY = new short[0];

    public static final double ELEVATION_SAMPLE_SPACING_METERS = 10;


    // Does geotools support mosaicing coverages together?

    public static void main (String[] args) throws Exception {

        // To check out in the debugger which ImageII implementations were loaded.
        IIORegistry registry = IIORegistry.getDefaultInstance();

        String rasterFile = "/Users/abyrd/Downloads/USGS_13_n34w118_int16_dm_nocompress.tif";
        AbstractGridFormat format = GridFormatFinder.findFormat(rasterFile);
        Hints hints = new Hints(Hints.FORCE_LONGITUDE_FIRST_AXIS_ORDER, Boolean.TRUE);
        var coverageReader = format.getReader(rasterFile, hints);
        GridCoverage2D rawCoverage = coverageReader.read(null);

        // Can interpolation instead be achieved with Hints.VALUE_INTERPOLATION_BICUBIC on the original raster?
        // GridCoverage2D interpolatedCoverage = Interpolator2D.create(rawCoverage, new InterpolationBilinear());
        GridCoverage2D interpolatedCoverage = rawCoverage;

        StreetLayer streets = loadStreetLayer("/Users/abyrd/geodata/la-reduced-1deg.osm.pbf");

        final LambdaCounter vertexCounter = new LambdaCounter(LOG, streets.vertexStore.getVertexCount(), 25_000,
                "Added elevation to {} of {} vertices.");

        streets.vertexStore.elevations = copyToTShortArrayList(
                IntStream.range(0, streets.vertexStore.getVertexCount())
                    .parallel()
                    .map(v -> {
                        VertexStore.Vertex vertex = streets.vertexStore.getCursor(v);
                        ElevationSampler sampler = new ElevationSampler(interpolatedCoverage);
                        vertexCounter.increment();
                        return (int) (sampler.readElevation(vertex.getLon(), vertex.getLat()));
                    }).toArray()
        );

        final LambdaCounter edgeCounter = new LambdaCounter(LOG, streets.edgeStore.nEdgePairs(), 10_000,
                "Added elevation to {} of {} edge pairs.");

        // DEVELOPMENT HACK: load only the first N edges to speed up loading.
        final int nEdgePairsToLoad = 50_000; // streets.edgeStore.nEdgePairs();

        // Anecdotally this parallel stream aproach is extremely effective. The speedup from parallelization seems to
        // far surpass any speedup from object reuse and avoiding garbage collection which are easier single-threaded.
        // Storing these as shorts is not as effective as storing the vertex elevations as shorts because edge profiles
        // are many small arrays, often with only a few elements.
        streets.edgeStore.elevationProfiles = IntStream.range(0, nEdgePairsToLoad)
                .parallel()
                .mapToObj(ep -> {
                    EdgeStore.Edge e = streets.edgeStore.getCursor(ep * 2);
                    edgeCounter.increment();
                    return ElevationSampler.profileForEdge(e, interpolatedCoverage);
                }).collect(Collectors.toList());

        // TODO filter out profiles for edges with near-constant slope. This may be an unnecessary optimization though.

        // Computing and averaging Tobler factors is extremely fast, on the order of 2 million edges per second.
        // It might be nice to bundle this into elevation sampling, but that's performed as a stream operation
        // and there's no obvious way to return both the profile and Tobler average.

        LOG.info("Averaging Tobler factors for all edges...");
        streets.edgeStore.toblerAverages = new TFloatArrayList();
        EdgeStore.Edge edge = streets.edgeStore.getCursor();
        for (int ep = 0; ep < nEdgePairsToLoad; ++ep) {
            edge.seek(ep * 2);
            streets.edgeStore.toblerAverages.add((float) ToblerCalculator.weightedAverageForEdge(edge));
        }
        LOG.info("Done averaging Tobler factors.");
    }

    private static StreetLayer loadStreetLayer (String osmSourceFile) {
        OSM osm = new OSM(osmSourceFile + ".mapdb");
        osm.intersectionDetection = true;
        osm.readFromFile(osmSourceFile);
        StreetLayer streetLayer = new StreetLayer(new TNBuilderConfig());
        streetLayer.loadFromOsm(osm);
        osm.close();
        return streetLayer;
    }

    // See http://osgeo-org.1560.x6.nabble.com/Combining-GridCoverages-td5077162.html
    private GridCoverage2D mergeTiles (GridCoverage2D... coverages) {
        // The envelope in world (not grid) coordinates.
        Envelope mergedEnvelope = new Envelope();
        // CoverageFactoryFinder.getGridCoverageFactory(null).create(
        for (GridCoverage2D coverage : coverages) {
            Envelope2D worldEnv = coverage.getEnvelope2D();
        }
        return null;
    }

    // A stateful elevation sampler. After creating it, feed it the vertices of a linestring one by one and it will
    // accumulate samples. Intended to be called with com.conveyal.r5.streets.EdgeStore.Edge.forEachPoint
    private static class ElevationSampler implements EdgeStore.PointConsumer {

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
            metersToNextPoint = ELEVATION_SAMPLE_SPACING_METERS;
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
                elevations.add(clampToShort((int) readElevation(currLon, currLat)));
                remainingLengthMeters -= metersToNextPoint;
                metersToNextPoint = ELEVATION_SAMPLE_SPACING_METERS;
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
//         * Call after all points have been consumed. This will compute a final slope cost for any short remaining piece.
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

        public static short[] profileForEdge (EdgeStore.Edge edge, GridCoverage2D coverage) {
            if (edge.getLengthMm() < ELEVATION_SAMPLE_SPACING_METERS * 1000) {
                return EMPTY_SHORT_ARRAY;
            }
            ElevationSampler sampler = new ElevationSampler(coverage);
            edge.forEachPoint(sampler);
            return sampler.elevations.toArray();
        }
    }

    /**
     * Although the JVM aligns objects to 8-byte boundaries, primitive values within arrays should take only their
     * true width. Packing integers into a short array should gennuinely make it half as big and keep more in cache.
     */
    public static TShortArrayList copyToTShortArrayList (int[] intArray) {
        TShortArrayList result = new TShortArrayList(intArray.length);
        for (int i : intArray) {
            result.add(clampToShort(i));
        }
        return result;
    }

    public static short clampToShort (int i) {
        short s = (short) i;
        if (s != i) {
            LOG.warn("int value overflowed short: {}", i);
        }
        return s;
    }
}
