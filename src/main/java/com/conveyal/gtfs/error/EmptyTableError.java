package com.conveyal.gtfs.error;

import java.io.Serializable;

/**
 * Created by landon on 4/5/17.
 */
public class EmptyTableError extends GTFSError implements Serializable {
    public static final long serialVersionUID = 1L;

    public EmptyTableError(String file) {
        super(file, 0, null);
    }

    @Override public String getMessage() {
        return String.format("Table is present in zip file, but it has no entries.");
    }
}
