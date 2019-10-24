package com.conveyal.r5.analyst;

/**
 * For use by FilePersistence. Avoids specifying bucket names or subfolders with Strings.
 */
public enum FileCategory {

    POLYGON, // Only this one is currently used, others are examples
    GRID,
    BUNDLE;

}
