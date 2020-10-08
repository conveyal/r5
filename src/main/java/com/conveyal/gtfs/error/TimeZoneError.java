package com.conveyal.gtfs.error;

import java.io.Serializable;

/**
 * Created by landon on 5/11/16.
 */

public class TimeZoneError extends GTFSError implements Serializable {
    public static final long serialVersionUID = 1L;

    public final String message;

    /**
     *
     * @param tableName name of table where error was found
     * @param line line of invalid timezone reference
     * @param field name of field for invalid timezone reference (agency_timezone or stop_timezone)
     * @param affectedEntityId stop or agency ID of the invalid timezone reference
     * @param message description of issue with timezone reference
     */
    public TimeZoneError(String tableName, long line, String field, String affectedEntityId, String message) {
        super(tableName, line, field, affectedEntityId);
        this.message = message;
    }

    @Override public String getMessage() {
        return message + ". (" + field + ": " + affectedEntityId + ")";
    }
}
