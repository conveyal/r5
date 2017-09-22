package com.conveyal.r5.api.util;

import com.conveyal.osmlib.OSMEntity;
import com.conveyal.r5.streets.StreetRouter;
import gnu.trove.map.TIntObjectMap;
import gnu.trove.map.hash.TIntObjectHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;

/**
 * Information about P+R parking lots
 * TODO rename me
 */
public class ParkRideParking implements Serializable {
    private static final Logger LOG = LoggerFactory.getLogger(ParkRideParking.class);
    //@notnull
    public Integer id;

    public String name;

    //Number of all spaces
    public Integer capacity;

    public float lat;
    public float lon;

    /**
     * List of closest stop, time
     */
    public TIntObjectMap<StreetRouter.State> closestTransfers = new TIntObjectHashMap<>();

    public ParkRideParking(int vertexIdx, double lat, double lon, OSMEntity way) {
        id = vertexIdx;
        if (way.hasTag("name")) {
            name = way.getTag("name");
        }

        if (way.hasTag("capacity")) {
            try {
                capacity = Integer.parseInt(way.getTag("capacity"));
            } catch (NumberFormatException nex) {
                capacity = null;
                LOG.info("Capacity in osm node/way:{} is {} instead of a number!", way.getTag("id"), way.getTag("capacity"));
            }
        }
        this.lat = (float) lat;
        this.lon = (float) lon;
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder("ParkRideParking{");
        sb.append("name='").append(name).append('\'');
        sb.append(", capacity=").append(capacity);
        sb.append('}');
        return sb.toString();
    }
}
