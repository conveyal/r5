package com.conveyal.gtfs.error;

import com.conveyal.gtfs.validator.model.Priority;

import java.io.Serializable;

/** Indicates that a field considered forbidden is present in a GTFS feed on a particular line. */
public class ForbiddenFieldError extends GTFSError implements Serializable {
    public static final long serialVersionUID = 1L;

    public ForbiddenFieldError (String file, long line, String field) {
        super(file, line, field);
    }

    @Override public String getMessage() {
        return String.format("A value was supplied for a forbidden column.");
    }

    @Override public Priority getPriority() {
        return Priority.MEDIUM;
    }
}
