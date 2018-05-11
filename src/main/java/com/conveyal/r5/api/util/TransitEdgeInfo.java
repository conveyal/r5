package com.conveyal.r5.api.util;

import com.vividsolutions.jts.geom.LineString;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * A transit network link; represents a physical link between 2 adjacent stops
 * including the geometry line between them.
 * This is similar to a StreetEdgeInfo.
 */
public class TransitEdgeInfo {
    /**
     * An ID synthesized from fromStopID, toStopID, and routeID.
     * IDs will change when the underlying GTFS data changes.
     * It takes the form routeID/fromStopID/toStopID
     */
    public String id;
    /**
     * The GTFS stop id the edge begins at. Multiple routes may use the same stop. This is unique
     * to the dataset (i.e. the transit agency, like SFMTA).
     */
    public int fromStopID;
    public List<ZonedDateTime> fromDepartureTime;

    public List<ZonedDateTime> toArrivalTime;
    /**
     * The GTFS stop id the edge ends at.
     */
    public int toStopID;
    /**
     * The GTFS routeID. This is unique to the dataset (i.e. the transit agency, like SFMTA).
     * It comes from GTFS prepended with the dataset name (which we've defined as
     * transit_agency_name_date_of_publication:routenumber, e.g. ttc_gtfs_5_oct_2017:52632).
     * */
    public String routeID;

    /**
     * The color to display on the map for the transit route.
     */
    public String routeColor;

    /** The geometry of this edge */
    public LineString geometry;

    public TransitEdgeInfo(int fromStopID, List<ZonedDateTime> fromDepartureTime, List<ZonedDateTime> toArrivalTime, int toStopID, String routeID, String routeColor, LineString geometry) {
        this.fromStopID = fromStopID;
        this.fromDepartureTime = fromDepartureTime;
        this.toArrivalTime = toArrivalTime;
        this.toStopID = toStopID;
        this.routeID = routeID;
        this.routeColor = routeColor;
        this.id = routeID + "/" + fromStopID + "/" + toStopID;
        this.geometry = geometry;
    }
}
