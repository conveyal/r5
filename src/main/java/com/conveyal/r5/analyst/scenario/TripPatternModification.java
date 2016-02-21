package com.conveyal.r5.analyst.scenario;

import com.conveyal.r5.transit.TransitLayer;
import com.conveyal.r5.transit.TripPattern;

import java.util.Collection;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * A kind of Modification that duplicates a TransitLayer and transforms its TripPatterns one by one.
 * It supplies a mechanism to target only certain TripPatterns based on their routeId or GTFS routeType (transport mode).
 * The filter fields are filled in by deserializing from JSON, and may be null if no filter criteria are supplied.
 * Any fields that is not supplied is a wildcard and matches all TripPatterns.
 *
 * This has been deprecated, I'm leaving it around to copy methods from later.
 */
@Deprecated
public abstract class TripPatternModification{

    /**
     * Agency ID to match. Each modification can apply to only one agency, as having multiple agencies
     * means route and trip IDs may not be unique.
     * FIXME this should really be a feedId but we need to keep the scenario API stable for now.
     */
    public String agencyId;

    /** Route IDs to match, or null for all. */
    public Collection<String> routeId;

    /**
     * Trip IDs to match, or null for all.
     * FIXME maybe this should be in the trip-level subclass but it works OK here.
     */
    public Collection<String> tripId;

    /** GTFS route types to match, see constants in com.conveyal.gtfs.model.Route */
    public int[] routeType;

    /**
     * Could any trip on the supplied trip pattern possibly match this filter?
     * This serves to reject entire patterns early before we scan through all their individual trips.
     */
    public boolean couldMatch (TripPattern pattern) {

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
    public boolean matches(String tripId) {

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

    public TransitLayer apply (TransitLayer originalTransitLayer) {
        // if (modNet.xyzList = network.xyzList)...
        TransitLayer transitLayer = originalTransitLayer.clone();
        transitLayer.tripPatterns = transitLayer.tripPatterns.stream()
                .map(this::applyToTripPattern)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        // FIXME we don't need to rebuild the street index here, only the pattern index.
        transitLayer.rebuildTransientIndexes();
        return transitLayer;
    }

    /* Method to be implemented by subclasses */

    /**
     * A method that transforms a single TripPattern in isolation, making protective copies as needed.
     * Returns the optionally transformed TripPattern or null if the TripPattern is to be removed entirely.
     */
    public abstract TripPattern applyToTripPattern(TripPattern originalTripPattern);

}
