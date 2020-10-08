package com.conveyal.r5.streets;

import com.conveyal.r5.api.util.BikeRentalStation;

import java.io.File;
import java.util.List;

/**
 * This used to load capital bikeshare XML from a file.
 * TODO implement loading GBFS from a URL
 */
public class BikeRentalBuilder {

    File file;

    public BikeRentalBuilder(File file) {
        this.file = file;
    }

    List<BikeRentalStation> getRentalStations() {
        throw new UnsupportedOperationException("IMPLEMENT GBFS!");
    }
}
