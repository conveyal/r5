package com.conveyal.r5.api.util;

/**
 * This is a response model class which holds data that will be serialized and returned to the client.
 * It is not used internally in routing.
 * It represents a single street edge in a series of on-street (walking/biking/driving) directions.
 * TODO could this be merged with WalkStep when profile routing and normal routing converge?
 */
public class StreetEdgeInfo {
    /**
     * OSM edge ID
     * @notnull
     */
    public Integer edgeId;

    /**
     * Distance of driving on these edge (meters)
     * @notnull
     */
    public Integer distance;
    //TODO: actual encoding and decoding of a geometry
    public PolylineGeometry geometry;

    /**
     * Which mode is used for driving (CAR, BICYCLE, WALK)
     */
    public NonTransitMode mode;
    public String streetName;
    public RelativeDirection relativeDirection;
    public AbsoluteDirection absoluteDirection;
    public Boolean stayOn;
    public Boolean area;

    /**
     * True if name is generated (cycleway, footway, sidewalk, etc)
     */
    public Boolean bogusName;

    //TODO: missing bikeRentalOn/Off station

    public Integer getDistance() {
        return distance;
    }
}
