package com.conveyal.gtfs.error;

import com.conveyal.gtfs.validator.model.Priority;

import java.io.Serializable;

/**
 * Created by landon on 10/14/16.
 */
public class TableInSubdirectoryError extends GTFSError implements Serializable {
    public static final long serialVersionUID = 1L;

    public final String directory;
    public final Priority priority = Priority.HIGH;

    public TableInSubdirectoryError(String file, String directory) {
        super(file, 0, null);
        this.directory = directory;
    }

    @Override public String getMessage() {
        return String.format("All GTFS files (including %s.txt) should be at root of zipfile, not nested in subdirectory (%s)", file, directory);
    }
}
