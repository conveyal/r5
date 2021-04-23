package com.conveyal.gtfs.error;

import com.conveyal.gtfs.validator.model.Priority;

import java.io.Serializable;

/** Represents a problem parsing an integer field of GTFS feed. */
public class NumberParseError extends GTFSError implements Serializable {
    public static final long serialVersionUID = 1L;

    public NumberParseError(String file, long line, String field) {
        super(file, line, field, Priority.HIGH);
    }

    @Override public String getMessage() {
        return String.format("Error parsing a number from a string.");
    }

}
