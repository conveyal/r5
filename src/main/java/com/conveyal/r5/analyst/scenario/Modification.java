package com.conveyal.r5.analyst.scenario;

import com.conveyal.r5.transit.TransportNetwork;
import com.conveyal.r5.transit.TripPattern;
import com.conveyal.r5.transit.TripSchedule;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import gnu.trove.list.TIntList;
import gnu.trove.list.array.TIntArrayList;
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
// Each class's getType should return the same value.
@JsonTypeInfo(use=JsonTypeInfo.Id.NAME, include=JsonTypeInfo.As.PROPERTY, property="type")
@JsonSubTypes({
        @JsonSubTypes.Type(name = "adjust-speed", value = AdjustSpeed.class),
        @JsonSubTypes.Type(name = "adjust-frequency", value = AdjustFrequency.class),
        @JsonSubTypes.Type(name = "adjust-dwell-time", value = AdjustDwellTime.class),
        @JsonSubTypes.Type(name = "add-trips", value = AddTrips.class),
        @JsonSubTypes.Type(name = "remove-trips", value = RemoveTrips.class),
        @JsonSubTypes.Type(name = "reroute", value = Reroute.class),
        @JsonSubTypes.Type(name = "remove-stops", value = RemoveStops.class),
        @JsonSubTypes.Type(name = "set-fare-calculator", value = SetFareCalculator.class),
        @JsonSubTypes.Type(name = "set-phase", value = SetPhase.class)
})
public abstract class Modification implements Serializable {

    static final long serialVersionUID = 2111604049617395839L;

    private static final Logger LOG = LoggerFactory.getLogger(Modification.class);

    /** Distinguish between modification types when a list of Modifications are serialized out as JSON. */
    public abstract String getType();

    /** This setter only exists to ensure that "type" is perceived as a property by libraries. It has no effect. */
    public final void setType (String type) {
        // Do nothing.
    }

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
     * Apply this single modification to a TransportNetwork.
     * The TransportNetwork should be pre-cloned by the caller, but may still contain references to collections
     * or other deep objects from the original TransportNetwork. The Modification is responsible for ensuring that
     * no damage is done to the original TransportNetwork by making copies of referenced objects as necessary.
     *
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
     * Any Modification that calls this method can potentially affect both the street and transit layers of the network.
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
        // and build the stop trees immediately in stopSpec::resolve.
        // We used to do this after every modification, but now we do it once at the end of the scenario application process
        /*network.streetLayer.buildEdgeLists();
        int firstNewStop = network.transitLayer.stopToVertexDistanceTables.size();
        for (int intStopIndex = firstNewStop; intStopIndex < network.transitLayer.getStopCount(); intStopIndex++) {
            network.transitLayer.stopToVertexDistanceTables.add(network.transitLayer.buildOneDistanceTable(intStopIndex));
        }
        new TransferFinder(network).findTransfers();*/
        return intStopIds;
    }

    /**
     * This determines the sequence in which modifications will be applied.
     * It is used in a standard compare function, so lower numbered modifications will be applied before higher ones.
     */
    @JsonIgnore
    public abstract int getSortOrder();

    /**
     * Enforces constraints on the list of routes, patterns, or trips that will be affected by a modification.
     * Only one of routes, trips, or patterns is defined, and sometimes trips are not allowed.
     * The IDs in the list must reference actual trip or route IDs in the target transport network.
     * @param allowTrips whether or not this modification allows specifying trips.
     */
    public void checkIds (Set<String> routes, Set<String> patterns, Set<String> trips, boolean allowTrips, TransportNetwork network) {

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
            errors.add("These route IDs could not be found: " + unmatchedRoutes.toString());
        }
        if (unmatchedTrips.size() > 0) {
            errors.add("These trip IDs could not be found: " + unmatchedTrips.toString());
        }

    }

}
