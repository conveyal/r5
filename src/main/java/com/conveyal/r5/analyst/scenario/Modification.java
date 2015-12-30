package com.conveyal.r5.analyst.scenario;

import com.conveyal.r5.streets.StreetLayer;
import com.conveyal.r5.transit.TransitLayer;
import com.conveyal.r5.transit.TransportNetwork;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.io.Serializable;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A Modification is a single change that can be applied while duplicating a TransportNetwork.
 * It allows comparing different scenarios without rebuilding entire networks from scratch.
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
        @JsonSubTypes.Type(name = "scale-speed", value = ScaleSpeed.class)
})
public abstract class Modification implements Serializable {

    /** Distinguish between modification types when a list of Modifications are serialized out as JSON. */
    public abstract String getType();

    /** Do nothing */
    public final void setType (String type) {
        /* do nothing */
    }

    public final Set<String> warnings = new HashSet<String>();

    /**
     * Apply this single modification to a TransportNetwork, making copies as necessary.
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
     * Resolve any GTFS IDs contained in this Modification to integer IDs in the given TransportNetwork.
     * I'm not sure this is really a good way to operate. Do we ever need to apply the same Modification to two
     * different TransportNetworks?
     */
    public void resolve (TransportNetwork network) {
        // Default implementation: do nothing.
    }

}
