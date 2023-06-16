package com.conveyal.gtfs.error;

import com.conveyal.gtfs.validator.model.Priority;

import java.io.Serializable;

/** Indicates that a GTFS entity was not added to a table because another object already exists with the same primary key. */
public class DuplicateKeyError extends GTFSError implements Serializable {
    public static final long serialVersionUID = 1L;

    public DuplicateKeyError(String file, long line, String field, String id) {
        super(file, line, field, id);
    }

    @Override public String getMessage() {
        return "Duplicate primary key.";
    }

    @Override public Priority getPriority() {
        return Priority.MEDIUM;
    }
}
