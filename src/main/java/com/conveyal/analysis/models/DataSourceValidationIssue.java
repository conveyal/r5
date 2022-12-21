package com.conveyal.analysis.models;

/**
 * Represents problems encountered while validating a newly uploaded DataSource.
 */
public class DataSourceValidationIssue {

    public Level level;

    public String description;

    public enum Level {
        ERROR, WARN, INFO
    }

    /** Zero argument constructor required for MongoDB driver automatic POJO deserialization. */
    public DataSourceValidationIssue () {

    }

    public DataSourceValidationIssue (Level level, String description) {
        this.level = level;
        this.description = description;
    }

}
