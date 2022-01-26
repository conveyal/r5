package com.conveyal.r5.rastercost;

import com.conveyal.osmlib.OSM;
import com.conveyal.r5.streets.EdgeStore;
import com.conveyal.r5.streets.StreetLayer;
import com.conveyal.r5.streets.VertexStore;
import com.conveyal.r5.util.LambdaCounter;
import gnu.trove.list.array.TFloatArrayList;
import gnu.trove.list.array.TShortArrayList;
import org.geotools.coverage.grid.GridCoverage2D;
import org.geotools.coverage.grid.io.AbstractGridFormat;
import org.geotools.coverage.grid.io.GridFormatFinder;
import org.geotools.geometry.Envelope2D;
import org.geotools.util.factory.Hints;
import org.locationtech.jts.geom.Envelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.spi.IIORegistry;
import java.io.File;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.conveyal.r5.streets.VertexStore.fixedDegreesToFloating;

/**
 * Note that integer decimeters provide enough resolution to compactly store elevation data up to the highest
 * inhabited places in the world in 16 bit integers. If signed they will not correctly represent places below sea level.
 *
 * Does geotools support mosaicing coverages together?
 */
public class ElevationLoader {

    private static final Logger LOG = LoggerFactory.getLogger(ElevationLoader.class);

    public static final short[] EMPTY_SHORT_ARRAY = new short[0];

    public static final double ELEVATION_SAMPLE_SPACING_METERS = 10;

    private final GridCoverage2D coverage;

    private ElevationLoader (GridCoverage2D coverage) {
        this.coverage = coverage;
    }

    public static ElevationLoader forFile (File rasterFile) {
        // this.rasterFile = rasterFile;
        rasterFile = new File("/Users/abyrd/Downloads/USGS_13_n34w118_int16_dm_nocompress.tif");
        AbstractGridFormat format = GridFormatFinder.findFormat(rasterFile);
        Hints hints = new Hints(Hints.FORCE_LONGITUDE_FIRST_AXIS_ORDER, Boolean.TRUE);
        var coverageReader = format.getReader(rasterFile, hints);
        try {
            GridCoverage2D coverage = coverageReader.read(null);
            return new ElevationLoader(coverage);
        } catch (Exception ex) {
            return null;
        }
    }

    public ElevationCostField load (StreetLayer streets) {
        // For debugging: To check out in the debugger which ImageII implementations were loaded.
        IIORegistry registry = IIORegistry.getDefaultInstance();

        // Can interpolation instead be achieved with Hints.VALUE_INTERPOLATION_BICUBIC on the original raster?
        // GridCoverage2D interpolatedCoverage = Interpolator2D.create(rawCoverage, new InterpolationBilinear());
        GridCoverage2D interpolatedCoverage = coverage;

        // Debug: load streets manually
        // StreetLayer streets = loadStreetLayer("/Users/abyrd/geodata/la-reduced-1deg.osm.pbf");

        ElevationCostField result = new ElevationCostField();

        final LambdaCounter vertexCounter = new LambdaCounter(LOG, streets.vertexStore.getVertexCount(), 25_000,
                "Added elevation to {} of {} vertices.");

        result.vertexElevationsDecimeters = copyToTShortArrayList(
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
        result.elevationProfiles = IntStream.range(0, nEdgePairsToLoad)
                .parallel()
                .mapToObj(ep -> {
                    EdgeStore.Edge e = streets.edgeStore.getCursor(ep * 2);
                    edgeCounter.increment();
                    return ElevationSampler.profileForEdge(e, interpolatedCoverage);
                }).collect(Collectors.toList());

        // TODO filter out profiles for edges with near-constant slope. This may be an unnecessary optimization though.

        LOG.info("Averaging Tobler factors for all edges...");
        result.computeToblerAverages(streets.edgeStore);
        LOG.info("Done averaging Tobler factors.");
        return result;
    }

    private static StreetLayer loadStreetLayer (String osmSourceFile) {
        OSM osm = new OSM(osmSourceFile + ".mapdb");
        osm.intersectionDetection = true;
        osm.readFromFile(osmSourceFile);
        StreetLayer streetLayer = new StreetLayer();
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
