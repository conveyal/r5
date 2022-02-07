package com.conveyal.r5.rastercost;

import com.conveyal.r5.streets.EdgeStore;
import com.conveyal.r5.streets.StreetLayer;
import com.conveyal.r5.util.LambdaCounter;
import gnu.trove.list.TFloatList;
import gnu.trove.list.array.TFloatArrayList;
import org.geotools.coverage.grid.GridCoverage2D;
import org.geotools.coverage.grid.io.AbstractGridFormat;
import org.geotools.coverage.grid.io.GridFormatFinder;
import org.geotools.util.factory.Hints;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.BitSet;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * This will reuse ElevationSampler#profileForEdge() to sample at very high resolution (1 meter) and return arrays
 * of shorts which will then be re-interpreted as true or false (nonzero or zero).
 * These will be stored as lists of distances at which the edge changes state from true to false. The value starts
 * as zero and can be toggled at any distance including zero itself.
 */
public class SunLoader implements CostField.Loader {

    private static final Logger LOG = LoggerFactory.getLogger(ElevationLoader.class);

    private final GridCoverage2D coverage;

    /** If the input contains shade rather than sun, then boolean values must be inverted. */
    private final boolean invert = true;

    private SunLoader (GridCoverage2D coverage) {
        this.coverage = coverage;
    }

    @Override
    public CostField load (StreetLayer streets) {
        final LambdaCounter edgeCounter = new LambdaCounter(LOG, streets.edgeStore.nEdgePairs(), 100_000,
                "Sampled sun/shade for {} of {} edge pairs.");

        List<BitSetWithSize> sunOnEdge = IntStream.range(0, streets.edgeStore.nEdgePairs())
            .parallel()
            .mapToObj(ep -> {
                EdgeStore.Edge e = streets.edgeStore.getCursor(ep * 2);
                edgeCounter.increment();
                return ElevationSampler.profileForEdge(e, coverage, 1.0);
            }).map(SunLoader::bitSetWithSizeFromShorts).collect(Collectors.toList());

        LOG.info("Computing sun proportions for all edges...");
        TFloatList sunProportions = new TFloatArrayList(sunOnEdge.size());
        for (BitSetWithSize soe : sunOnEdge) {
            sunProportions.add(((float)soe.bitSet.cardinality()) / soe.size);
        }
        LOG.info("Done computing sun proportions.");
        SunCostField result = new SunCostField(2.0, 1.0);
        result.sunOnEdge = sunOnEdge.stream().map(s -> s.bitSet).collect(Collectors.toList());
        result.sunProportions = sunProportions;
        return result;
    }

    public static BitSet bitSetFromShorts (short[] shortArray, boolean invert) {
        BitSet bitSet = new BitSet(shortArray.length);
        boolean allFalse = true;
        boolean allTrue = true;
        for (int i = 0; i < shortArray.length; i++) {
            boolean sun = shortArray[i] != 0;
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

    private static BitSetWithSize bitSetWithSizeFromShorts (short[] shorts) {
        BitSet bitSet = bitSetFromShorts(shorts, true);
        int size = shorts.length;
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
        // FIXME this was broken because many entries were sharing the same "all true" and "all false" arrays.
        void invert () {
            bitSet.flip(0, size);
        }
    }

    public static SunLoader forFile (File rasterFile) {
        AbstractGridFormat format = GridFormatFinder.findFormat(rasterFile);
        Hints hints = new Hints(Hints.FORCE_LONGITUDE_FIRST_AXIS_ORDER, Boolean.TRUE);
        var coverageReader = format.getReader(rasterFile, hints);
        try {
            GridCoverage2D coverage = coverageReader.read(null);
            return new SunLoader(coverage);
        } catch (Exception ex) {
            return null;
        }
    }

}
