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
        return false; // No errors occurred.
    }

    @Override
    public boolean apply (TransportNetwork network) {
        TransitLayer transitLayer = network.transitLayer;
        for (StopSpec stopSpec : stops) {
            int newVertexIndex = network.streetLayer.getOrCreateVertexNear(stopSpec.lat, stopSpec.lon);
            transitLayer.stopIdForIndex.add(stopSpec.id); // indexForStopId will be derived from this
            transitLayer.stopNames.add(stopSpec.name);
            transitLayer.streetVertexForStop.add(newVertexIndex); // stopForStreetVertex will be derived from this
        }
        return false; // No errors occurred.
    }

    public static class StopSpec {
        public String id;
        public String name;
        public double lat;
        public double lon;
    }

}
