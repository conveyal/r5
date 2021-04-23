package com.conveyal.gtfs.error;

import com.conveyal.gtfs.validator.model.Priority;

import java.io.Serializable;

/** Represents any GTFS loading problem that does not have its own class, with a free-text message. */
public class GeneralError extends GTFSError implements Serializable {
    public static final long serialVersionUID = 1L;

    private String message;

    public GeneralError(String file, long line, String field, String message) {
        super(file, line, field, Priority.UNKNOWN);
        this.message = message;
    }

    @Override public String getMessage() {
        return message;
    }

}
