package com.conveyal.analysis.results;

/**
 * Although these correspond exactly to the subclasses of CSV writer, which seems like a red flag in Java, these
 * do serve to enumerate the acceptable parameters coming over the HTTP API.
 */
public enum CsvResultType {
    ACCESS, TIMES, PATHS
}
