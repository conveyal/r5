package com.conveyal.r5.rastercost;

import com.conveyal.r5.streets.StreetLayer;
import org.geotools.coverage.grid.GridCoverage2D;
import org.geotools.coverage.grid.io.AbstractGridFormat;
import org.geotools.coverage.grid.io.GridFormatFinder;
import org.geotools.util.factory.Hints;

import java.io.File;

public class TreeLoader implements CostField.Loader {

    private final GridCoverage2D coverage;

    private TreeLoader (GridCoverage2D coverage) {
        this.coverage = coverage;
    }

    @Override
    public CostField load (StreetLayer streets) {
        return new SunCostField();
    }

    public static TreeLoader forFile (File rasterFile) {
        AbstractGridFormat format = GridFormatFinder.findFormat(rasterFile);
        Hints hints = new Hints(Hints.FORCE_LONGITUDE_FIRST_AXIS_ORDER, Boolean.TRUE);
        var coverageReader = format.getReader(rasterFile, hints);
        try {
            GridCoverage2D coverage = coverageReader.read(null);
            return new TreeLoader(coverage);
        } catch (Exception ex) {
            return null;
        }
    }

}
