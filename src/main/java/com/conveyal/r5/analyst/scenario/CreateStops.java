package com.conveyal.r5.analyst.scenario;

import com.conveyal.r5.streets.StreetLayer;
import com.conveyal.r5.transit.TransitLayer;
import com.conveyal.r5.transit.TransportNetwork;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;

/**
 * Add some transit stops to the TransportNetwork that can be referenced by their string identifiers.
 * This Modification just adds them to the graph for subsequent use in other Modifications.
 * It makes no changes to any trips or patterns.
 */
public class CreateStops extends Modification {

    public static final long serialVersionUID = 1L;

    private static final Logger LOG = LoggerFactory.getLogger(CreateStops.class);

    /** Stops to create and insert into the TransportNetwork. */
    public Collection<StopSpec> stops;

    @Override
    public String getType() {
        return "create-stops";
    }

    @Override
    public boolean resolve (TransportNetwork network) {
        // TODO Check for stop ID collisions.
        return false;
    }

    @Override
    public boolean apply (TransportNetwork network) {
        TransitLayer transitLayer = network.transitLayer.clone();
        // Pull the following out into a method on TransportNetwork
        StreetLayer streetLayer = network.streetLayer;
        if (!streetLayer.edgeStore.isProtectiveCopy()) {
            streetLayer = streetLayer.extendOnlyCopy();
        }
        // This bidirectional reference is not going to work right unless transitlayer or both are always cloned.
        streetLayer.linkedTransitLayer = transitLayer;
        transitLayer.linkedStreetLayer = streetLayer;
        for (StopSpec stopSpec : stops) {
            int newVertexIndex = streetLayer.getOrCreateVertexNear(stopSpec.lat, stopSpec.lon);
            transitLayer.stopIdForIndex.add(stopSpec.id); // indexForStopId will be derived from this
            transitLayer.stopNames.add(stopSpec.name);
            transitLayer.streetVertexForStop.add(newVertexIndex); // stopForStreetVertex will be derived from this
        }
        network.streetLayer = streetLayer;
        return true;
    }

    public static class StopSpec {
        public String id;
        public String name;
        public double lat;
        public double lon;
    }

}
