package com.conveyal.gtfs.validator;

import com.conveyal.gtfs.GTFSFeed;
import com.conveyal.gtfs.error.EmptyFieldError;
import com.conveyal.gtfs.error.ForbiddenFieldError;
import com.conveyal.gtfs.error.GeneralError;
import com.conveyal.gtfs.error.RangeError;
import com.conveyal.gtfs.error.ReferentialIntegrityError;
import com.conveyal.gtfs.model.Stop;

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
        validateStopStationConstraints();
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

    /** Validate location_type and parent_station constraints as well as referential integrity. */
    private void validateStopStationConstraints () {
        for (Stop stop : feed.stops.values()) {
            for (Rule rule : rules) {
                if (rule.check(stop, feed)) break;
            }
        }
    }

    private static final String FILE = "stops.txt";
    private static final String FIELD = "parent_station";

    /** GTFS location_type codes. */
    private enum LocType {
        STOP(0),
        STATION(1),
        ENTRANCE(2),
        GENERIC(3),
        BOARDING(4);
        public final int code;
        LocType (int code) {
            this.code = code;
        }
    }

    private enum Requirement {
        OPTIONAL, REQUIRED, FORBIDDEN
    }

    private static class Rule {
        // The location_type to which the rule applies.
        LocType fromLocType;
        // Whether the parent_station is required, forbidden, or optional.
        Requirement parentRequirement;
        // If the parent_station is present and not forbidden, what location_type it must be.
        LocType parentLocType;

        public Rule (LocType fromLocType, Requirement parentRequirement, LocType parentLocType) {
            this.fromLocType = fromLocType;
            this.parentRequirement = parentRequirement;
            this.parentLocType = parentLocType;
        }

        /**
         * Call this method once for each location_type, defining a rules about that location_type's parent_station.
         * @return true if this rule validated the stop, or false if more rules must be checked
         */
        public boolean check (Stop stop, GTFSFeed feed) {
            // If this rule does not apply to this stop, do not perform checks.
            if (stop.location_type != fromLocType.code) {
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
                    if (parentStation.location_type != parentLocType.code) {
                        feed.errors.add(new RangeError(
                                FILE, stop.sourceFileLine, FIELD,
                                parentLocType.code, parentLocType.code,
                                parentStation.location_type)
                        );
                    }
                }
            }
            return true;
        }
    }

    private static final List<Rule> rules = List.of(
            new Rule(LocType.STOP,     Requirement.OPTIONAL,  LocType.STATION),
            new Rule(LocType.STATION,  Requirement.FORBIDDEN, LocType.STATION),
            new Rule(LocType.ENTRANCE, Requirement.REQUIRED,  LocType.STATION),
            new Rule(LocType.GENERIC,  Requirement.REQUIRED,  LocType.STATION),
            new Rule(LocType.BOARDING, Requirement.REQUIRED,  LocType.STOP)
    );

}
