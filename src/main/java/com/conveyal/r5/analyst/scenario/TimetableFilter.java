package com.conveyal.r5.analyst.scenario;

import com.conveyal.r5.transit.TripPattern;
import com.google.common.primitives.Ints;

import java.util.Collection;

/**
 * Abstract class for modifications to existing timetables/frequencies.
 *
 * This superclass imparts a mechanism to target modifications to certain routes, trips, and GTFS routeTypes (transport modes).
 */
public abstract class TimetableFilter extends Modification {

    /**
     * Agency ID to match. Each modification can apply to only a single agency, as having multiple agencies
     * means route and trip IDs may not be unique.
     * FIXME this should be feedId but we need to keep the API stable for now.
     */
    public String agencyId;

    /** Route IDs to match, or null for all */
    public Collection<String> routeId;

    /** Trip IDs to match, or null for all */
    public Collection<String> tripId;

    /** GTFS route types to match, see constants in com.conveyal.gtfs.model.Route */
    public int[] routeType;

    /**
     * Could any trip on the supplied trip pattern possibly match this filter?
     * This serves to reject entire patterns early before we scan through all their individual trips.
     */
    protected boolean couldMatch (TripPattern pattern) {

        /* FIXME R5 currently has no agency info in patterns
        if (!pattern.route.getAgency().getId().equals(agencyId))
            return false;
        */

        // FIXME everything should be scoped by a feed name somehow
        if (routeId != null && !routeId.contains(pattern.routeId)) {
            return false;
        }

        /* FIXME R5 currently stores no routeType info
        if (routeType != null && !Ints.contains(routeType, pattern.route.getType()))
            return false;
        */

        return true;
    }

    /**
     * Does the supplied Trip match the criteria defined in this instance?
     * FIXME we can't pass Trips around, gtfs-lib stores too much information in them.
     * FIXME everything should be scoped by a feed name somehow
     */
    protected boolean matches(String tripId) {

        /*
        Route route = trip.getRoute();

        if (!route.getAgency().getId().equals(agencyId))
            return false;

        if (routeId != null && !routeId.contains(route.getId().getId()))
            return false;
        */

        if (this.tripId != null && !this.tripId.contains(tripId)) {
            return false;
        }

        /*
        if (routeType != null && !Ints.contains(routeType, trip.getRoute().getType()))
            return false;
        */

        return true;
    }
}
