package com.conveyal.r5.rastercost;

import com.conveyal.r5.streets.Edge;
import com.conveyal.r5.streets.StreetLayer;
import com.conveyal.util.LambdaCounter;
import gnu.trove.list.TFloatList;
import gnu.trove.list.array.TFloatArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandles;
import java.util.BitSet;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * This reuses the same raster sampler as the ElevationLoader to sample at very high resolution (1 meter) and return
 * arrays of doubles which will then be re-interpreted as true or false (nonzero or zero).
 */
public class SunLoader implements CostField.Loader<SunCostField> {

    private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    /** If the input contains shade rather than sun, then boolean values must be inverted. */
    private final boolean invert = true;

    private final RasterDataSourceSampler rasterSampler;

    private double outputScale = 1;

    public SunLoader (RasterDataSourceSampler rasterSampler) {
        this.rasterSampler = rasterSampler;
    }

    @Override
    public SunCostField load (StreetLayer streets) {
        final LambdaCounter edgeCounter = new LambdaCounter(LOG, streets.edgeStore.nEdgePairs(), 100_000,
                "Sampled sun/shade for {} of {} edge pairs.");

        List<BitSetWithSize> sunOnEdge = IntStream.range(0, streets.edgeStore.nEdgePairs())
            .parallel()
            .mapToObj(ep -> {
                Edge e = streets.getEdgeCursor(ep * 2);
                edgeCounter.increment();
                return rasterSampler.sampleEdge(e);
            }).map(SunLoader::bitSetWithSizeFromDoubles).collect(Collectors.toList());

        LOG.info("Computing sun proportions for all edges...");
        TFloatList sunProportions = new TFloatArrayList(sunOnEdge.size());
        for (BitSetWithSize soe : sunOnEdge) {
            sunProportions.add(((float)soe.bitSet.cardinality()) / soe.size);
        }
        LOG.info("Done computing sun proportions.");
        SunCostField result = new SunCostField(outputScale, 1.0);
        result.sunOnEdge = sunOnEdge.stream().map(s -> s.bitSet).collect(Collectors.toList());
        result.sunProportions = sunProportions;
        return result;
    }

    /** Given an array of double-precision values, return a BitSet containing the indexes of all nonzero array elements. */
    public static BitSet bitSetFromDoubles (double[] doubleArray, boolean invert) {
        BitSet bitSet = new BitSet(doubleArray.length);
        boolean allFalse = true;
        boolean allTrue = true;
        for (int i = 0; i < doubleArray.length; i++) {
            boolean sun = doubleArray[i] != 0;
            if (invert) sun = !sun;
            if (sun) {
                allFalse = false;
                bitSet.set(i);
            } else {
                allTrue = false;
            }
        }
        if (allTrue) {
            bitSet = SunCostField.ALL_TRUE;
        }
        if (allFalse) {
            bitSet = SunCostField.ALL_FALSE;
        }
        return bitSet;
    }

    private static BitSetWithSize bitSetWithSizeFromDoubles (double[] doubles) {
        BitSet bitSet = bitSetFromDoubles(doubles, true);
        int size = doubles.length;
        // FIXME This is ugly, handle differently.
        if (bitSet == SunCostField.ALL_FALSE || bitSet == SunCostField.ALL_TRUE) {
            size = 1;
        }
        return new BitSetWithSize(bitSet, size);
    }

    /**
     * BitSets are implemented as a set of integers (the set bit indexes), not as a list of booleans of finite length.
     * This is a simple way to restrict the range of indexes over which the bitset is defined.
     * It serves as a compound return type from the parallelized code.
     */
    private static class BitSetWithSize {
        final BitSet bitSet;
        final int size;
        public BitSetWithSize (BitSet bitSet, int size) {
            this.bitSet = bitSet;
            this.size = size;
        }
        /** Be careful about calling this on shared BitSets. We should really use shared BitSetWithSize instances. */
        void invert () {
            bitSet.flip(0, size);
        }
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
        if (inputScale != 1) {
            LOG.warn("Scaling input of a sun/shade raster has no effect because it only sees zero/nonzero.");
        }
    }

    @Override
    public void setOutputScale (double outputScale) {
        this.outputScale = outputScale;
    }
}
