package com.conveyal.file;

import java.util.Locale;

/**
 * Just to keep things organized and easier to find when debugging/manually manipulating files, each file we put into
 * storage has a category, corresponding to the subdirectory or sub-bucket where it's stored in cache and/or on S3.
 */
public enum FileCategory {

    BUNDLES, GRIDS, RESULTS, RESOURCES, POLYGONS, TAUI;

    /** @return a String for the directory or sub-bucket name containing all files in this category. */
    public String directoryName () {
        return this.name().toLowerCase(Locale.ROOT);
    };

}
