package com.conveyal.r5.rastercost;

import com.conveyal.r5.streets.StreetLayer;
import org.geotools.coverage.grid.GridCoverage2D;
import org.geotools.coverage.grid.io.AbstractGridFormat;
import org.geotools.coverage.grid.io.GridFormatFinder;
import org.geotools.util.factory.Hints;

import java.io.File;

/**
 * This will reuse ElevationSampler#profileForEdge() to sample at very high resolution (1 meter) and return arrays
 * of shorts which will then be re-interpreted as true or false (nonzero or zero).
 * These will be stored as lists of distances at which the edge changes state from true to false. The value starts
 * as zero and can be toggled at any distance including zero itself.
 */
public class SunLoader implements CostField.Loader {

    private final GridCoverage2D coverage;

    private SunLoader (GridCoverage2D coverage) {
        this.coverage = coverage;
    }

    @Override
    public CostField load (StreetLayer streets) {
        return new SunCostField(2.0, 1.0);
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
