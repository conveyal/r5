package com.conveyal.r5.analyst.scenario;

import com.conveyal.r5.transit.TransportNetwork;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
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
        @JsonSubTypes.Type(name = "add-stops", value = AddStops.class),
        @JsonSubTypes.Type(name = "remove-stops", value = RemoveStops.class),
        @JsonSubTypes.Type(name = "transfer-rule", value = TransferRule.class),
        @JsonSubTypes.Type(name = "set-trip-phasing", value = SetTripPhasing.class),
        // HACK: this modification type should only be instantiated as a member of SetTripPhasing, and Jackson shouldn't
        // need type info because there is no polymorphism where it is used. Why, one might ask, is the class that selects
        // a frequency entry a Modification? Because it recycles the code that matches trip patterns and trips, that's why.
        @JsonSubTypes.Type(name = "frequency-entry-selector", value= SetTripPhasing.FrequencyEntrySelector.class)
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
    public final Set<String> warnings = new HashSet<String>();

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
     * the errors encountered should be added to the list of Strings in the Modification field "warnings".
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

}
