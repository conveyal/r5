package com.conveyal.r5.analyst.scenario;

import com.conveyal.r5.profile.StreetMode;
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

    /** The radius within which we will search for roads to attach the new stops. */
    public double radiusMeters = 200;

    private transient TransportNetwork network;

    @Override
    public String getType() {
        return "create-stops";
    }

    @Override
    public boolean resolve (TransportNetwork network) {
        // Check for duplicate stop IDs.
        return false;
    }

    @Override
    protected TransitLayer applyToTransitLayer(TransitLayer originalTransitLayer) {
        // This should be called before applyToStreetLayer.
        TransitLayer transitLayer = originalTransitLayer.clone();
        StreetLayer streetLayer = transitLayer.linkedStreetLayer; //.clone();
        // streetLayer.transitLayer
        // transitLayer.linkedStreetLayer = streetLayer;
        for (StopSpec stopSpec : stops) {
            int newVertexIndex = streetLayer.getOrCreateVertexNear(stopSpec.lat, stopSpec.lon, radiusMeters, false,
                StreetMode.WALK);
            transitLayer.stopIdForIndex.add(stopSpec.id); // indexForStopId will be derived from this
            transitLayer.stopNames.add(stopSpec.name);
            transitLayer.streetVertexForStop.add(newVertexIndex); // stopForStreetVertex will be derived from this
        }
        return transitLayer;
    }

    @Override
    protected StreetLayer applyToStreetLayer(StreetLayer originalStreetLayer) {
        return null;
    }

    public static class StopSpec {
        String id;
        String name;
        double lat;
        double lon;
    }

}
