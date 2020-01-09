package com.conveyal.r5.analyst.scenario;

import com.conveyal.r5.common.GeometryUtils;
import com.conveyal.r5.profile.StreetMode;
import com.vividsolutions.jts.geom.Coordinate;
import com.vividsolutions.jts.geom.Point;
import gnu.trove.list.TIntList;

import java.util.HashMap;
import java.util.Map;

/**
 * This is the internal form of a PickupDelay modification that has been resolved against a particular TransportNetwork.
 */
public class PickupWaitTimes {

    private final IndexedPolygonCollection polygons;

    /**
     * This Map associates each on-demand zone with specific stops in the TransitLayer.
     * On the access leg, someone picked up in the key zone can be dropped off
     * On the egress end, this is reversed.
     * If the Map is not present (null) then all the zonePolygons allow access to all stopPolygons; if the stopPolygons
     * are also not supplied then all zones allow access to any stop in the network.
     */
    private final Map<ModificationPolygon, TIntList> stopNumbersForZonePolygon;

    public final StreetMode streetMode;

    public PickupWaitTimes (
        IndexedPolygonCollection polygons,
        Map<ModificationPolygon, TIntList> stopNumbersForZonePolygon,
        StreetMode streetMode
    ) {
        this.polygons = polygons;
        this.stopNumbersForZonePolygon = stopNumbersForZonePolygon;
        this.streetMode = streetMode;
    }

    /**
     * @return the wait time to be picked up at the given point, or -1 if no service is available.
     */
    public int getWaitTime(double lat, double lon) {
        Point point = GeometryUtils.geometryFactory.createPoint(new Coordinate(lon, lat));
        ModificationPolygon polygon = polygons.getWinningPolygon(point);
        if (polygon == null || polygon.data == -1) {
            return -1;
        } else {
            return (int) (polygon.data * 60);
        }
    }

}
