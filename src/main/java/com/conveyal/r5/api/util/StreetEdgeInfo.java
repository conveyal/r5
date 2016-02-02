package com.conveyal.r5.api.util;

import com.vividsolutions.jts.geom.LineString;

/**
 * This is a response model class which holds data that will be serialized and returned to the client.
 * It is not used internally in routing.
 * It represents a single street edge in a series of on-street (walking/biking/driving) directions.
 * TODO could this be merged with WalkStep when profile routing and normal routing converge?
 */
public class StreetEdgeInfo {
    /**
     * OTP internal edge ID
     * @notnull
     */
    public Integer edgeId;

    /**
     * Distance of driving on these edge (milimeters)
     * @notnull
     */
    public int distance;
    /** The geometry of this edge */
    public LineString geometry;

    /**
     * Which mode is used for driving (CAR, BICYCLE, WALK)
     */
    public NonTransitMode mode;
    public String streetName;
    public RelativeDirection relativeDirection;
    public AbsoluteDirection absoluteDirection;
    public boolean stayOn = false;
    public Boolean area;
    //Exit name when exiting highway or roundabout
    public String exit;

    /**
     * True if name is generated (cycleway, footway, sidewalk, etc)
     */
    public Boolean bogusName;

    public BikeRentalStation bikeRentalOnStation;
    public BikeRentalStation bikeRentalOffStation;
    //TODO: P+R

    public void setDirections(double lastAngle, double thisAngle, boolean roundabout) {
        relativeDirection = RelativeDirection.setRelativeDirection(lastAngle, thisAngle, roundabout);
        setAbsoluteDirection(thisAngle);
    }

    public void setAbsoluteDirection(double thisAngle) {
        thisAngle = Math.toRadians(thisAngle);
        int octant = (int) (8 + Math.round(thisAngle * 8 / (Math.PI * 2))) % 8;
        absoluteDirection = AbsoluteDirection.values()[octant];
    }

}
