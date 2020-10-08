package com.conveyal.gtfs.storage;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.BitSet;
import java.util.zip.GZIPInputStream;

/**
 * Loads an ESRI ASCII grid containing integers and allows looking up values as booleans (where > 0).
 * This is used for
 */
public class BooleanAsciiGrid {

    int ncols;
    int nrows;
    double xllcorner;
    double yllcorner;
    double cellsize;
    double nodata;

    BitSet grid;

    public BooleanAsciiGrid (InputStream inputStream, boolean gzipped) {

        try {
            if (gzipped) inputStream = new GZIPInputStream(inputStream);
            BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
            boolean inHeader = true;
            int nDataItemsRead = 0;
            for(String line = reader.readLine(); line != null; line = reader.readLine()) {
                String[] fields = line.trim().split("\\s+"); // split on one or more whitespace characters
                if (inHeader) {
                    inHeader = handleHeaderRow(fields);
                    if (inHeader) continue;
                    grid = new BitSet(ncols * nrows);
                }
                if (fields.length != ncols) {
                    throw new RuntimeException("Wrong number of data columns: " +  fields.length);
                }
                for (String field : fields) {
                    int value = Integer.parseInt(field);
                    grid.set(nDataItemsRead++, value > 0);
                }
            }
            if (nDataItemsRead != ncols * nrows) throw new RuntimeException("Too few data items: " + nDataItemsRead);
        } catch (IOException exception) {
            throw new RuntimeException(exception);
        }
    }

    /**
     * Get a grid for places where population density is over 5 people per square kilometer.
     * We use the Gridded Population of the World v3 data set for 2015 UN-adjusted population density.
     * This data set was downloaded at 1/4 degree resolution in ESRI ASCII grid format. The grid file was edited
     * manually to eliminate the no-data value header, since I could not find a way to operate on no-value cells in the
     * QGIS raster calculator. Then the raster calculator in QGIS was used with the formula ("glds00ag15@1" > 5),
     * which makes all cells with population density above the threshold have a value of one,
     * and all others a value of zero (since the no data value in the grid is -9999). This was then exported as another
     * ASCII grid file, which zips well. The license for this data set is Creative Commons Attribution.
     * See http://sedac.ciesin.columbia.edu/data/collection/gpw-v3
     */
    public static BooleanAsciiGrid forEarthPopulation() {
        try {
            InputStream gridStream = BooleanAsciiGrid.class.getResourceAsStream("gpwv3-quarter-boolean.asc");
            return new BooleanAsciiGrid(gridStream, false);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    /**
     * @param fields
     * @return whether we appear to still be in the header lines of the file
     */
    private boolean handleHeaderRow (String[] fields) {
        if (fields.length != 2) return false;
        String key = fields[0];
        if ("ncols".equals(key)) {
            ncols = Integer.parseInt(fields[1]);
        } else if ("nrows".equalsIgnoreCase(key)) {
            nrows = Integer.parseInt(fields[1]);
        } else if ("xllcorner".equalsIgnoreCase(key)) {
            xllcorner = Double.parseDouble(fields[1]);
        } else if ("yllcorner".equalsIgnoreCase(key)) {
            yllcorner = Double.parseDouble(fields[1]);
        } else if ("cellsize".equalsIgnoreCase(key)) {
            cellsize = Double.parseDouble(fields[1]);
        } else if ("NODATA_value".equalsIgnoreCase(key)) {
            nodata = Double.parseDouble(fields[1]);
        } else {
            return false;
        }
        return true;
    }

    public boolean getValueForCoords (double x, double y) {
        int xCell = (int)((x - xllcorner) / cellsize);
        int yCell = (int)((y - yllcorner) / cellsize);
        int index = (nrows - yCell - 1) * ncols + xCell; // Vertical flip
        if (index < 0 || index > grid.length()) return false;
        return grid.get(index);
    }

}
