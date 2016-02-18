package com.conveyal.r5.analyst.scenario;

import com.conveyal.r5.streets.StreetLayer;
import com.conveyal.r5.transit.TransitLayer;
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
        @JsonSubTypes.Type(name = "remove-trip", value = RemoveTrip.class),
        @JsonSubTypes.Type(name = "adjust-headway", value = AdjustHeadway.class),
        @JsonSubTypes.Type(name = "adjust-dwell-time", value = AdjustDwellTime.class),
        @JsonSubTypes.Type(name = "skip-stop", value = SkipStop.class),
        @JsonSubTypes.Type(name = "add-trip-pattern", value = AddTripPattern.class),
        @JsonSubTypes.Type(name = "convert-to-frequency", value = ConvertToFrequency.class),
        @JsonSubTypes.Type(name = "transfer-rule", value = TransferRule.class),
        @JsonSubTypes.Type(name = "scale-speed", value = ScaleSpeed.class),
        @JsonSubTypes.Type(name = "insert-stop", value = InsertStop.class)
})
public abstract class Modification implements Serializable {

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
     * Apply this single modification to a TransportNetwork, making copies as necessary.
     * TODO pre-clone the network so this function doesn't need to return a TransportNetwork, and can return a success/error value.
     */
    public TransportNetwork applyToTransportNetwork(TransportNetwork originalNetwork) {
        // We don't need a base implementation to return the unmodified TransportNetwork.
        // Any Modification should produce a protective copy at least at level 0.
        TransportNetwork network = originalNetwork.clone();
        network.transitLayer = this.applyToTransitLayer(network.transitLayer);
        network.streetLayer = this.applyToStreetLayer(network.streetLayer);
        //network.gridPointSet = this.gridPointSet; // apply modification?
        return network;
    }

    /**
     * Apply this Modification to a TransitLayer, making protective copies of the TransitLayer's elements as needed.
     */
    protected abstract TransitLayer applyToTransitLayer (TransitLayer originalTransitLayer);

    /**
     * Apply this Modification to a StreetLayer, making protective copies of the StreetLayer's elements as needed.
     */
    protected abstract StreetLayer applyToStreetLayer (StreetLayer originalStreetLayer);

    /**
     * Implementations of this function on concrete Modification classes should do three things:
     * 1. Infer the canonical internal representation of any parameters e.g. convert frequencies to periods in seconds.
     * 2. Resolve any GTFS IDs contained in this Modification to integer IDs in the given TransportNetwork.
     * 3. Sanity check the resulting values to allow early failure, avoiding missing identifiers, infinite loops etc.
     *
     * A particular Modification instance should only be used by one thread on a single TransportNetwork, so it is fine
     * to store the canonical parameter representations and resolved IDs in instance fields of Modification subclasses.
     *
     * The function should return true if it encounters an out of range, missing, or otherwise problematic parameter,
     * rather than throwing an exception. Besides providing cleaner program flow, this allows us to accumulate and
     * provide more error messages at once, rather than reporting only the first error encountered. Messages describing
     * the errors encountered should be added to the list of Strings in the field "warnings".
     */
    public boolean resolve (TransportNetwork network) {
        // Default implementation: do nothing and affirm that there are no known problems with the parameters.
        return false;
    }

    /**
     * Scenarios can have variants, which allow switching on and off individual modifications within the scenario.
     * This avoids cutting and pasting lots of modifications into separate scenarios to represent each variant, which is
     * tedious and error prone. When scenario JSON is being written by hand, it also leads to more coherent, unified,
     * and easily modifiable collections of variants.
     *
     * Modifications that are not declared to be part of any particular variant are always active.
     * If no variant is chosen when the scenario is used, all modifications associated with variants are disabled.
     */
    public boolean isActiveInVariant (String variant) {
        // If this modification is not associated with any variant, it is always used.
        if (activeInVariants == null) return true;
        // This modification is associated with a set of variants (possibly the empty set).
        // If the specified scenario is one of these, then the modification is active.
        // If the specified scenario is null (no scenario is chosen) then this modification is not active.
        return activeInVariants.contains(variant);
    }

}
