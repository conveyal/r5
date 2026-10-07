package com.conveyal.gtfs.error;

import com.conveyal.gtfs.validator.model.Priority;

import java.io.Serializable;

/// A GTFS Flex feature is present that this software does not (yet) support.
public class UnsupportedFlexError extends GTFSError implements Serializable {
    public static final long serialVersionUID = 1L;

    /// Feeds may still be useful even if they define flex service we don't support.
    public static final Priority PRIORITY = Priority.MEDIUM;
    private final String message;

    public UnsupportedFlexError (String file, long line, String field, String message) {
        super(file, line, field);
        this.message = message;
    }

    @Override public String getMessage() {
        return message;
    }

    @Override public Priority getPriority() {
        return PRIORITY;
    }
}
