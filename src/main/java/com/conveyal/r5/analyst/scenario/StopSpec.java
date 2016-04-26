package com.conveyal.r5.analyst.scenario;

import com.conveyal.r5.transit.TransitLayer;
import com.conveyal.r5.transit.TransportNetwork;

import java.io.Serializable;
import java.util.Set;

/**
 * This represents either an existing or a new stop in Modifications when creating or inserting stops into routes.
 * If the id already exists, the existing stop is used. If not, a new stop is created.
*/
public class StopSpec implements Serializable {

    public static final long serialVersionUID = 1L;

    /** create a stop spec for a particular stop ID */
    public StopSpec (String id) {
        this.id = id;
    }

    /** Create a stop spec at a particular location */
    public StopSpec (double lon, double lat) {
        this.lat = lat;
        this.lon = lon;
    }

    /** default constructor for deserialization */
    public StopSpec () {
        /* do nothing */
    }

    public String id;
    public String name;
    public double lat;
    public double lon;

    /**
     * Given a specification for a transit stop, which can be a reference to an existing stop or the location and
     * name of a new stop, find or create the stop in the given network. This should in fact be a protective scenario
     * copy of the network to avoid trashing the original baseline network.
     * TODO maybe make protective copies a subclass of Networks to enforce this typing.
     *
     * @param network the transit network in which to find or create the specified transit stops.
     * @return the integer index of the new stop within the given network, or -1 if it could not be created.
     */
    public int resolve (TransportNetwork network, Set<String> warnings) {
        if (id == null) {
            // No stop ID supplied, this is a new stop rather than a reference to an existing one.
            if (lat == 0 || lon == 0) {
                warnings.add("When no stop ID is supplied, nonzero coordinates must be supplied.");
            }
            int newStopId = materializeOne(network);
            return newStopId;
        } else {
            // Stop ID supplied, this is a reference to an existing stop rather than a new stop.
            if (lat != 0 || lon != 0 || name != null) {
                warnings.add("A reference to an existing id should not include coordinates or a name.");
            }
            int intStopId = network.transitLayer.indexForStopId.get(id);
            if (intStopId == -1) {
                warnings.add("Could not find existing stop with GTFS ID " + id);
            }
            return intStopId;
        }
    }

    /**
     *  This follows the model of com.conveyal.r5.streets.StreetLayer.associateStops()
     *  We reuse the method that is employed when the graph is first built, because we actually want to create
     *  a new unique street vertex exactly at the supplied coordinate (which represents the stop itself) then
     *  make edges that connect that stop to a splitter vertex on the street (which is potentially shared/reused).
     *  @return the integer ID of the newly created stop
     */
    private int materializeOne (TransportNetwork network) {
        int stopVertex = network.streetLayer.createAndLinkVertex(lat, lon);
        TransitLayer transitLayer = network.transitLayer;
        int newStopId = transitLayer.getStopCount();
        transitLayer.stopIdForIndex.add(this.id); // indexForStopId will be derived from this
        transitLayer.stopNames.add(this.name);
        transitLayer.streetVertexForStop.add(stopVertex); // stopForStreetVertex will be derived from this
        return newStopId;
    }

}
