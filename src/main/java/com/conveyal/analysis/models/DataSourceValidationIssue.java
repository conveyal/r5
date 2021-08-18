package com.conveyal.analysis.models;

public abstract class DataSourceValidationIssue {

    public abstract String description();

    public abstract Level level();

    public enum Level {
        ERROR, WARN, INFO
    }

}
