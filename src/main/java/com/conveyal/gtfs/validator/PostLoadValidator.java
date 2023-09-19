package com.conveyal.gtfs.validator;

import com.conveyal.gtfs.GTFSFeed;
import com.conveyal.gtfs.error.EmptyFieldError;
import com.conveyal.gtfs.error.ForbiddenFieldError;
import com.conveyal.gtfs.error.GeneralError;
import com.conveyal.gtfs.error.RangeError;
import com.conveyal.gtfs.error.ReferentialIntegrityError;
import com.conveyal.gtfs.error.SuspectStopLocationError;
import com.conveyal.gtfs.model.Stop;
import com.conveyal.gtfs.storage.BooleanAsciiGrid;

import java.util.List;

/**
 * Currently we perform a lot of validation while we're loading the rows out of the input GTFS feed.
 * This can only catch certain categories of problems. Other problems must be found after tables are fully loaded.
 * These include self-referential tables like stops (which reference other stops as parent_stations).
 * This could also be expressed as a postLoadValidation method on Entity.Loader.
 *
 * In the original RDBMS-enabled gtfs-lib we had a lot of validation classes that would perform checks after loading.
 * This included more complex semantic checks of the kind we do in r5 while building networks. We might want to
 * re-import those gtfs-lib validators and adapt them to operate on MapDB only for the purposes of r5.
 * However, validating a feed takes a lot of sorting and grouping of stop_times that will need to be repeated when we
 * build a network. It's debatable whether we should make the user wait twice for this, as it's one of the slower steps.
 */
public class PostLoadValidator {

    private GTFSFeed feed;

    public PostLoadValidator (GTFSFeed feed) {
        this.feed = feed;
    }

    public void validate () {
        validateCalendarServices();
        validateParentStations();
        validateStopPopulationDensity();
    }

    /**
     * calendars and calendar_dates are the only conditionally required tables: at least one of the two must be present.
     * The underlying requirement is that at least some service is defined.
     */
    private void validateCalendarServices () {
        if (feed.services.isEmpty()) {
            feed.errors.add(new GeneralError("calendar.txt", 0, "service_id",
                    "Feed does not define any services in calendar or calendar_dates."));
        }
    }

    /**
     * Validate that stops are not in locations with no people. This can happen from incorrect coordinate transforms
     * into WGS84. Stops are often located on "null island" at (0,0). This can also happen in other coordinate systems
     * before they are transformed to WGS84: the origin of a common French coordinate system is in the Sahara.
     */
    private void validateStopPopulationDensity () {
        BooleanAsciiGrid popGrid = BooleanAsciiGrid.forEarthPopulation();
        for (Stop stop : feed.stops.values()) {
            if (!(popGrid.getValueForCoords(stop.stop_lon, stop.stop_lat))) {
                feed.errors.add(new SuspectStopLocationError(stop.stop_id, stop.sourceFileLine));
            }
        }
    }

    /**
     * Validate location_type and parent_station constraints as well as referential integrity.
     * Individual validation actions like this could be factored out into separate classes (PostLoadValidators)
     * but as long as we only have two or three of them they can live together as methods in this one class.
     */
    private void validateParentStations() {
        for (Stop stop : feed.stops.values()) {
            for (ParentStationRule parentStationRule : PARENT_STATION_RULES) {
                if (parentStationRule.check(stop, feed)) break;
            }
        }
    }

    private static final String FILE = "stops.txt";
    private static final String FIELD = "parent_station";

    /** GTFS location_type codes. */
    private enum LocationType {
        STOP(0),
        STATION(1),
        ENTRANCE(2),
        GENERIC(3),
        BOARDING(4);
        public final int code;
        LocationType(int code) {
            this.code = code;
        }
    }

    private enum Requirement {
        OPTIONAL, REQUIRED, FORBIDDEN
    }

    /**
     * These rules are used specifically for validating parent_stations on stops.txt entries.
     * For now we only have two kind of post-load validation, so these classes specific to one kind of validation
     * are all grouped together under the PostLoadValidator class.
     */
    private static class ParentStationRule {
        // The location_type to which the constraint applies.
        LocationType fromLocationType;
        // Whether the parent_station is required, forbidden, or optional.
        Requirement parentRequirement;
        // If the parent_station is present and not forbidden, what location_type it must be.
        LocationType parentLocationType;

        public ParentStationRule(
                LocationType fromLocationType,
                Requirement parentRequirement,
                LocationType parentLocationType
        ) {
            this.fromLocationType = fromLocationType;
            this.parentRequirement = parentRequirement;
            this.parentLocationType = parentLocationType;
        }

        /**
         * Call this method on rules for each location_type in turn, enforcing the rule when the supplied stop matches.
         * @return true if this rule validated the stop, or false if more rules for other location_types must be checked.
         */
        public boolean check (Stop stop, GTFSFeed feed) {
            // If this rule does not apply to this stop (does not apply to its location_type), do not perform checks.
            if (stop.location_type != fromLocationType.code) {
                return false;
            }
            if (stop.parent_station == null) {
                if (parentRequirement == Requirement.REQUIRED) {
                    feed.errors.add(new EmptyFieldError(FILE, stop.sourceFileLine, FIELD));
                }
            } else {
                // Parent station reference was supplied.
                if (parentRequirement == Requirement.FORBIDDEN) {
                    feed.errors.add(new ForbiddenFieldError(FILE, stop.sourceFileLine, FIELD));
                }
                Stop parentStation = feed.stops.get(stop.parent_station);
                if (parentStation == null) {
                    feed.errors.add(new ReferentialIntegrityError(FILE, stop.sourceFileLine, FIELD, stop.parent_station));
                } else {
                    if (parentStation.location_type != parentLocationType.code) {
                        feed.errors.add(new RangeError(
                                FILE, stop.sourceFileLine, FIELD,
                                parentLocationType.code, parentLocationType.code,
                                parentStation.location_type)
                        );
                    }
                }
            }
            return true;
        }
    }

    /**
     * Representation in code of the constraints described at https://gtfs.org/schedule/reference/#stopstxt
     * subsections on location_type and parent_station.
     */
    private static final List<ParentStationRule> PARENT_STATION_RULES = List.of(
            new ParentStationRule(LocationType.STOP,     Requirement.OPTIONAL,  LocationType.STATION),
            new ParentStationRule(LocationType.STATION,  Requirement.FORBIDDEN, LocationType.STATION),
            new ParentStationRule(LocationType.ENTRANCE, Requirement.REQUIRED,  LocationType.STATION),
            new ParentStationRule(LocationType.GENERIC,  Requirement.REQUIRED,  LocationType.STATION),
            new ParentStationRule(LocationType.BOARDING, Requirement.REQUIRED,  LocationType.STOP)
    );

}
