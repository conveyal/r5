package com.conveyal.r5.analyst.scenario;

import com.conveyal.r5.transit.TransportNetwork;
import com.conveyal.r5.transit.TripPattern;
import com.conveyal.r5.transit.TripSchedule;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.annotation.JsonTypeIdResolver;
import gnu.trove.list.TIntList;
import gnu.trove.list.array.TIntArrayList;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A Modification is a single change that can be applied while duplicating a TransportNetwork.
 * It allows comparing different scenarios without rebuilding entire networks from scratch.
 *
 * TODO make it clear that Modifications are throw-away objects that should be applied only once to a single TransportNetwork
 * That will also allow them to accumulate stats on how many patterns, trips, routes etc. they affected.
 */
// we use the property "type" to determine what type of modification it is. The string values are defined here.
@JsonTypeInfo(use=JsonTypeInfo.Id.CUSTOM, include=JsonTypeInfo.As.PROPERTY, property="type", visible = true)
@JsonTypeIdResolver(ModificationTypeResolver.class)
public abstract class Modification implements Serializable {

    // TODO remove all serialVersionUIDs unless we're actually relying on them
    // static final long serialVersionUID = 2111604049617395839L;

    private static final Logger LOG = LoggerFactory.getLogger(Modification.class);

    /** Free-text comment describing this modification instance and what it's intended to do or represent. */
    public String comment;

    /**
     * If non-null, which variants of the scenario include this modification.
     * If null, the modification will always be applied in any variant.
     */
    public List<String> activeInVariants;

    /**
     * The "resolve" method is called on each Modification before it is applied. If any problems are detected, the
     * Modification should not be applied, and this Set should contain Strings describing all the problems.
     */
    public transient final Set<String> errors = new HashSet<>();

    /**
     * Any warnings that should be presented to the user but which do not prevent scenario application should appear here.
     */
    // TODO this should be transient as well but previously wasn't. R5 modifications are stored in MongoDB in regional
    // analyses, which means that marking it transient causes deserialization headaches.
    public final Set<String> warnings = new HashSet<>();

    /**
     * As the modification is applied, information can be stored here to allow the end user to confirm its effects.
     * For example, the number of transit stops affected or streets modified.
     */
    public transient final Set<String> info = new HashSet<>();

    /**
     * Apply this single modification to a TransportNetwork.
     * The TransportNetwork should be pre-cloned by the caller, but may still contain references to collections
     * or other deep objects from the original TransportNetwork. The Modification is responsible for ensuring that
     * no damage is done to the original TransportNetwork by making copies of referenced objects as necessary.
     *
     * TODO arguably this and resolve() don't need to return booleans - all implementations just check whether the errors list is empty, so we could just supply a method that does that.
     * TODO remove the network field, and use the network against which this Modification was resolved.
     * @return true if any errors happened while applying the modification.
     */
    public abstract boolean apply (TransportNetwork network);

    /**
     * Implementations of this function on concrete Modification classes should do three things:
     * 1. Infer the canonical internal representation of any parameters e.g. convert frequencies to periods in seconds.
     * 2. Resolve any GTFS IDs contained in this Modification to integer IDs in the given TransportNetwork.
     * 3. Sanity check the resulting values to allow early failure, avoiding missing identifiers, infinite loops etc.
     *
     * A particular Modification instance should only be used by one thread on a single TransportNetwork, so it is fine
     * to store the canonical parameter representations and resolved IDs in instance fields of Modification subclasses.
     * We return a boolean rather than throwing an exception to provide cleaner program flow. This also allows
     * accumulation of error messages, rather than reporting only the first error encountered. Messages describing
     * the errors encountered should be added to the list of Strings in the Modification field "errors".
     *
     * The resolve step could conceivably be folded into the actual application of the modification to the network,
     * but by resolving all the modifications up front before applying any, we are able to accumulate multiple errors
     * and return them to the user all at once, simplifying debugging.
     *
     * @return true if an out of range, missing, or otherwise problematic parameter is encountered.
     */
    public boolean resolve (TransportNetwork network) {
        // Default implementation: do nothing and affirm that there are no known problems with the parameters.
        return false;
    }

    /**
     * See Scenario::affectsStreetLayer
     * @return true if this modification will result in changes to the StreetLayer of the TransportNetwork.
     */
    public boolean affectsStreetLayer() {
        return false;
    }

    /**
     * See Scenario::affectsTransitLayer
     * @return true if this modification will result in changes to the TransitLayer of the TransportNetwork.
     */
    public boolean affectsTransitLayer() {
        return true;
    }

    /**
     * For each StopSpec in the supplied list, find or create and link a stop in the given TransportNetwork.
     * This method is shared by all modifications that need to find or create stops based on a list of StopSpecs.
     * Any warnings or error messages are stored in the errors list of this Modification.
     * Any Modification that calls this method can potentially affect both the street and transit layers of the network,
     * so affectsStreetLayer and affectsTransitLayer should both return true on such Modifications.
     * @return the integer stop IDs of all stops that were found or created
     */
    protected TIntList findOrCreateStops(List<StopSpec> stops, TransportNetwork network) {
        // Create or find the stops referenced by the new trips.
        TIntList intStopIds = new TIntArrayList();
        for (StopSpec stopSpec : stops) {
            int intStopId = stopSpec.resolve(network, errors);
            intStopIds.add(intStopId);
        }
        // Adding the stops changes the street network but does not rebuild the edge lists.
        // We have to rebuild the edge lists after those changes but before we build the stop trees.
        // Alternatively we could actually update the edge lists as edges are added and removed,
        // and build the stop trees immediately in stopSpec::resolve. We used to do this after every modification,
        // but now we do it once at the end of the scenario application process
        return intStopIds;
    }

    /**
     * This determines the sequence in which modifications will be applied. It is used in a standard compare function,
     * so lower numbered modifications will be applied before higher ones. Typical values are 10 through 100.
     */
    @JsonIgnore
    public abstract int getSortOrder();

    /**
     * Enforces constraints on the list of routes, patterns, or trips that will be affected by a modification.
     * Only one of routes, trips, or patterns is defined, and sometimes trips are not allowed.
     * The IDs in the list must reference actual trip or route IDs in the target transport network.
     * @param allowTrips whether or not this modification allows specifying trips.
     */
    public void checkIds (Set<String> routes, Set<String> patterns, Set<String> trips, boolean allowTrips,
                          TransportNetwork network) {

        // We will remove items from these sets as they are encountered when iterating over all trips.
        Set<String> unmatchedRoutes = new HashSet<>();
        Set<String> unmatchedTrips = new HashSet<>();

        // Check that only one of routes, trips, or patterns is defined, and initialize the unmatched ID sets.
        int nDefined = 0;
        if (routes != null && routes.size() > 0) {
            nDefined += 1;
            unmatchedRoutes.addAll(routes);
        }
        if (patterns != null && patterns.size() > 0) {
            nDefined += 1;
            unmatchedTrips.addAll(patterns); // The pattern IDs are actually trip IDs for example trips on patterns.
        }
        if (trips!= null && trips.size() > 0) {
            nDefined += 1;
            unmatchedTrips.addAll(trips);
        }
        if (nDefined != 1) {
            if (allowTrips) {
                errors.add("Exactly one of routes, patterns, or trips must be provided.");
            } else {
                errors.add("Either routes or patterns must be provided, but not both.");
            }
        }
        if (!allowTrips && trips != null) {
            // This cannot happen yet, but it will if (TODO) we pull these fields up to the Modification class.
            errors.add("This modification type does not allow specifying individual trips by ID.");
        }

        // TODO pull the network field up into the Modification class.
        for (TripPattern pattern : network.transitLayer.tripPatterns) {
            unmatchedRoutes.remove(pattern.routeId);
            for (TripSchedule schedule : pattern.tripSchedules) {
                unmatchedTrips.remove(schedule.tripId);
            }
        }
        if (unmatchedRoutes.size() > 0) {
            if (unmatchedRoutes.size() == routes.size()) {
                errors.add("None of the specified route IDs could be found.");
            } else {
                // When a GTFS feed contains routes that have no stoptimes (which is unfortunately common) R5 will not
                // represent that route. When someone bulk-adds all the routes in that feed to a modification,
                // some of them may be valid while others are not.
                // Specifying some routes that don't exist seems like a severe problem that should be an error rather
                // than a warning, but for now we have to tolerate it for the above reason.
                warnings.add("Some of the specified route IDs could be found: " + unmatchedRoutes.toString());
            }
        }
        if (unmatchedTrips.size() > 0) {
            errors.add("These trip IDs could not be found: " + unmatchedTrips.toString());
        }

    }

    /**
     * Record a warning if a point is not within the region covered by a TransportNetwork.
     * TODO use this on all other Modification subtypes that handle coordinates.
     * @param coordinate in decimal degrees, with axis order (lon, lat) corresponding to (x, y).
     * @param network the TransportNetwork in which the coordinate will be used.
     */
    protected void rangeCheckCoordinate (Coordinate coordinate, TransportNetwork network) {
        // Envelope is precomputed, and is fast to fetch.
        Envelope envelope = network.getEnvelope();
        if (!envelope.contains(coordinate)) {
            String message = String.format(
                    "Coordinate %s was not within the area covered by the transport network (%s).",
                    coordinate.toString(),
                    envelope.toString()
            );
            errors.add(message);
        }
    }
}
