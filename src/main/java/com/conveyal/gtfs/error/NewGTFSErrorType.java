package com.conveyal.gtfs.error;

import com.conveyal.gtfs.validator.model.Priority;

public enum NewGTFSErrorType {

    DATE_FORMAT(Priority.MEDIUM, "Date format should be YYYYMMDD."),
    DATE_RANGE(Priority.MEDIUM, "Date should is extremely far in the future or past."),
    TIME_FORMAT(Priority.MEDIUM, "Time format should be HH:MM:SS."),
    URL_FORMAT(Priority.MEDIUM, "URL format should be <scheme>://<authority><path>?<query>#<fragment>"),
    LANGUAGE_FORMAT(Priority.LOW, "Language should be specified with a valid BCP47 tag."),
    INTEGER_FORMAT(Priority.MEDIUM, "Incorrect integer format."),
    FLOATING_FORMAT(Priority.MEDIUM, "Incorrect floating point number format."),
    COLUMN_NAME_UNSAFE(Priority.HIGH, "Column header contains characters not safe in SQL, it was renamed."),
    NUMBER_NEGATIVE(Priority.MEDIUM, "Number was expected to be non-negative."),
    NUMBER_TOO_SMALL(Priority.MEDIUM, "Number was below the allowed range."),
    NUMBER_TOO_LARGE(Priority.MEDIUM, "Number was above the allowed range."),
    DUPLICATE_ID(Priority.MEDIUM, "More than one entity in a table had the same ID."),
    DUPLICATE_TRIP(Priority.MEDIUM, "More than one trip had an identical schedule and stops."),
    DUPLICATE_STOP(Priority.MEDIUM, "More than one stop was located in exactly the same place."),
    DUPLICATE_HEADER(Priority.MEDIUM, "More than one column in a table had the same name in the header row."),
    MISSING_TABLE(Priority.MEDIUM, "This table is required by the GTFS specification but is missing."),
    MISSING_COLUMN(Priority.MEDIUM, "A required column was missing from a table."),
    MISSING_SHAPE(Priority.MEDIUM, "???"),
    MISSING_FIELD(Priority.MEDIUM, "A required field was missing or empty in a particular row."),
    WRONG_NUMBER_OF_FIELDS(Priority.MEDIUM, "A row did not have the same number of fields as there are headers in its table."),
    OVERLAPPING_TRIP(Priority.MEDIUM, "Blocks?"),
    SHAPE_REVERSED(Priority.MEDIUM, "A shape appears to be intended for vehicles running the opposite direction on the route."),
    SHAPE_MISSING_COORDINATE(Priority.MEDIUM, "???"),
    TABLE_IN_SUBDIRECTORY(Priority.HIGH, "Rather than being at the root of the zip file, a table was nested in a subdirectory."),
    TABLE_TOO_LONG(Priority.MEDIUM, "Table is too long to record line numbers with a 32-bit integer, overflow will occur."),
    TIME_ZONE_FORMAT(Priority.MEDIUM, "Time zone format should be X."),
    ROUTE_DESCRIPTION_SAME_AS_NAME(Priority.LOW, "The description of a route is identical to its name, so does not add any information."),
    ROUTE_LONG_NAME_CONTAINS_SHORT_NAME(Priority.LOW, "The long name of a route should complement the short name, not include it."),
    ROUTE_SHORT_AND_LONG_NAME_MISSING(Priority.MEDIUM, "A route has neither a long nor a short name."),
    ROUTE_SHORT_NAME_TOO_LONG(Priority.MEDIUM, "The short name of a route is too long for display in standard GTFS consumer applications."),
    STOP_LOW_POPULATION_DENSITY(Priority.HIGH, "A stop is located in a geographic area with very low human population density."),
    STOP_GEOGRAPHIC_OUTLIER(Priority.HIGH, "This stop is located very far from the middle 90% of stops in this feed."),
    STOP_UNUSED(Priority.MEDIUM, "This stop is not referenced by any trips."),
    TRIP_EMPTY(Priority.HIGH, "This trip is defined but has no stop times."),
    ROUTE_UNUSED(Priority.HIGH, "This route is defined but has no trips."),
    TRAVEL_DISTANCE_ZERO(Priority.MEDIUM, "The vehicle does not cover any distance between the last stop and this one."),
    TRAVEL_TIME_NEGATIVE(Priority.HIGH, "The vehicle arrives at this stop before it departs from the previous one."),
    TRAVEL_TIME_ZERO(Priority.HIGH, "The vehicle arrives at this stop at the same time it departs from the previous stop."),
    MISSING_ARRIVAL_OR_DEPARTURE(Priority.MEDIUM, "First and last stop times are required to have both an arrival and departure time."),
    TRIP_TOO_FEW_STOP_TIMES(Priority.MEDIUM, "A trip must have at least two stop times to represent travel."),
    TRIP_OVERLAP_IN_BLOCK(Priority.MEDIUM, "Blocks"),
    TRAVEL_TOO_SLOW(Priority.MEDIUM, "The vehicle is traveling very slowly to reach this stop from the previous one."),
    TRAVEL_TOO_FAST(Priority.MEDIUM, "The vehicle travels extremely fast to reach this stop from the previous one."),
    DEPARTURE_BEFORE_ARRIVAL(Priority.MEDIUM, "The vehicle departs from this stop before it arrives."),
    REFERENTIAL_INTEGRITY(Priority.HIGH, "This line references an ID that does not exist in the target table."),
    OTHER(Priority.LOW, "Other errors.");

    public final Priority priority;
    public final String englishMessage;

    NewGTFSErrorType(Priority priority, String englishMessage) {
        this.priority = priority;
        this.englishMessage = englishMessage;
    }

}


