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

    public DataSourceValidationIssue (Level level, String description) {
        this.level = level;
        this.description = description;
    }

}
