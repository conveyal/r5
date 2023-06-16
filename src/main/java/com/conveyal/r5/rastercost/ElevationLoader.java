package com.conveyal.r5.rastercost;

import com.conveyal.r5.rastercost.ElevationCostField.ElevationCostCalculator;
import com.conveyal.r5.streets.EdgeStore;
import com.conveyal.r5.streets.StreetLayer;
import com.conveyal.r5.streets.VertexStore;
import com.conveyal.r5.util.LambdaCounter;
import gnu.trove.list.array.TShortArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandles;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.conveyal.r5.rastercost.ElevationCostField.DECIMETERS_PER_METER;

/**
 * Note that integer decimeters provide enough resolution to compactly store elevation data up to the highest
 * inhabited places in the world in 16 bit integers. If signed they will not correctly represent places below sea level.
 *
 * Does geotools support mosaicing coverages together?
 */
public class ElevationLoader implements CostField.Loader<ElevationCostField> {

    private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    public static final short[] EMPTY_SHORT_ARRAY = new short[0];

    public static final double ELEVATION_SAMPLE_SPACING_METERS = 10;

    private final ElevationCostCalculator costCalculator;

    private final RasterDataSourceSampler rasterSampler;

    private double outputScale;

    public ElevationLoader (String dataSourceId, ElevationCostCalculator costCalculator) {
        // It could be useful to be able to configure interpolation.
        // We never want it for the 1/0 tree shade raster, but may or may not want it for elevation.
        this.rasterSampler = new RasterDataSourceSampler(dataSourceId, ELEVATION_SAMPLE_SPACING_METERS, false);
        this.costCalculator = costCalculator;
    }

    @Override
    public ElevationCostField load (StreetLayer streets) {
        // For debugging: To check out in the debugger which ImageII implementations were loaded.
        // IIORegistry registry = IIORegistry.getDefaultInstance();

        ElevationCostField result = new ElevationCostField(costCalculator, outputScale);

        final LambdaCounter vertexCounter = new LambdaCounter(LOG, streets.vertexStore.getVertexCount(), 100_000,
                "Added elevation to {} of {} vertices.");

        result.vertexElevationsDecimeters = toDecimeterTShortArrayList(
                IntStream.range(0, streets.vertexStore.getVertexCount())
                        .parallel()
                        .mapToDouble(v -> {
                            VertexStore.Vertex vertex = streets.vertexStore.getCursor(v);
                            vertexCounter.increment();
                            return rasterSampler.readElevation(vertex.getLon(), vertex.getLat());
                        }).toArray()
        );

        final LambdaCounter edgeCounter = new LambdaCounter(LOG, streets.edgeStore.nEdgePairs(), 100_000,
                "Added elevation to {} of {} edge pairs.");

        // Anecdotally this parallel stream approach is extremely effective. The speedup from parallelization seems to
        // far surpass any speedup from object reuse and avoiding garbage collection which are easier single-threaded.
        // Storing these as shorts is not as effective as storing the vertex elevations as shorts because edge profiles
        // are many small arrays, often with only a few elements.
        result.elevationProfilesDecimeters = IntStream.range(0, streets.edgeStore.nEdgePairs())
                .parallel()
                .mapToObj(ep -> {
                    EdgeStore.Edge e = streets.edgeStore.getCursor(ep * 2);
                    edgeCounter.increment();
                    return rasterSampler.sampleEdge(e);
                })
                .map(ElevationLoader::toDecimeterArray)
                .collect(Collectors.toList());

        // TODO filter out profiles for edges with near-constant slope. This may be an unnecessary optimization though.

        LOG.info("Averaging Tobler factors for all edges...");
        result.computeWeightedAverages(streets.edgeStore);
        LOG.info("Done averaging Tobler factors.");
        return result;
    }

    /**
     * Although the JVM aligns objects to 8-byte boundaries, primitive values within arrays should take only their
     * true width. Packing rounded doubles into a short array should genuinely make it 1/4 as big and keep more in cache.
     */
    public static TShortArrayList toDecimeterTShortArrayList (double[] doubleArray) {
        TShortArrayList result = new TShortArrayList(doubleArray.length);
        for (double d : doubleArray) {
            result.add(clampToShort(Math.round(d * DECIMETERS_PER_METER)));
        }
        return result;
    }

    public static short[] toDecimeterArray (double[] doubleArray) {
        if (doubleArray.length == 0) {
            return EMPTY_SHORT_ARRAY;
        }
        short[] result = new short[doubleArray.length];
        for (int i = 0; i < doubleArray.length; i++) {
            result[i] = clampToShort(Math.round(doubleArray[i] * DECIMETERS_PER_METER));
        }
        return result;
    }

    public static short clampToShort (long l) {
        short s = (short) l;
        if (s != l) {
            LOG.warn("int value overflowed short: {}", l);
        }
        return s;
    }


    @Override
    public void setNorthShiftMeters (double northShiftMeters) {
        rasterSampler.setNorthShiftMeters(northShiftMeters);
    }

    @Override
    public void setEastShiftMeters (double eastShiftMeters) {
        rasterSampler.setEastShiftMeters(eastShiftMeters);
    }

    @Override
    public void setInputScale (double inputScale) {
        rasterSampler.setInputScale(inputScale);
    }

    @Override
    public void setOutputScale (double outputScale) {
        this.outputScale = outputScale;
    }

}
