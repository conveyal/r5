package com.conveyal.gtfs.error;

import com.conveyal.gtfs.validator.model.Priority;

import java.io.Serializable;

/** Indicates that a GTFS entity was not added to a table because it had no primary key. */
public class MissingKeyError extends GTFSError implements Serializable {
    public static final long serialVersionUID = 1L;

    public MissingKeyError(String file, long line, String field) {
        super(file, line, field);
    }

    @Override public String getMessage() {
        return "Missing primary key.";
    }

    @Override public Priority getPriority() {
        return Priority.MEDIUM;
    }
}
