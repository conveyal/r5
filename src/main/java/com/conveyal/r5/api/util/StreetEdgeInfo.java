package com.conveyal.r5.api.util;

import com.conveyal.r5.common.GeometryUtils;
import com.fasterxml.jackson.annotation.JsonIgnore;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.LineString;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

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
    @JsonIgnore //This is currently needed for StreetSegmentTest Because it tries to load AbsoluteDirection as a Double for unknown reason
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

    public ParkRideParking parkRide;

    public void setDirections(double lastAngle, double thisAngle, boolean roundabout) {
        relativeDirection = RelativeDirection.setRelativeDirection(lastAngle, thisAngle, roundabout);
        setAbsoluteDirection(Math.toRadians(thisAngle));
    }

    public void setAbsoluteDirection(double thisAngle) {
        int octant = (int) (8 + Math.round(thisAngle * 8 / (Math.PI * 2))) % 8;
        absoluteDirection = AbsoluteDirection.values()[octant];
    }

    @Override
    public String toString() {
        String sb = "StreetEdgeInfo{" + "edgeId=" + edgeId +
            ", distance=" + distance +
            ", geometry=" + geometry +
            ", mode=" + mode +
            ", streetName='" + streetName + '\'' +
            ", relativeDirection=" + relativeDirection +
            ", absoluteDirection=" + absoluteDirection +
            ", stayOn=" + stayOn +
            ", area=" + area +
            ", exit='" + exit + '\'' +
            ", bogusName=" + bogusName +
            ", bikeRentalOnStation=" + bikeRentalOnStation +
            ", bikeRentalOffStation=" + bikeRentalOffStation +
            '}';
        return sb;
    }

    /**
     * Returns true if pair of streetEdgeInfos have same:
     * - mode
     * - bikeRentalOnStation
     * - bikeRentalOffStation
     * - (both have stayOn true AND both have relativeDirection as CONTINUE
     * - OR both have CIRCLE_CLOCKWISE or CIRCLE_COUNTERCLOCKWISE)
     *
     * Joining of streetEdges with different relative directions in roundabouts is needed
     * since only the first relativeDirection is correct in roundabout.
     *
     *
     * This is used to find out which two consecutive StreetEdges to join together
     * to show as one step in Turn by turn directions
     *
     * It is called from {@link StreetSegment#compactEdges()}
     * @param streetEdgeInfo
     * @return true if similar
     */
    boolean similarTo(StreetEdgeInfo streetEdgeInfo) {
        boolean bothSimilar = mode.equals(streetEdgeInfo.mode)
            && bikeRentalOnStation == streetEdgeInfo.bikeRentalOnStation
            && bikeRentalOffStation == streetEdgeInfo.bikeRentalOffStation;
        boolean normalSimilar = relativeDirection == RelativeDirection.CONTINUE
            && streetEdgeInfo.relativeDirection == RelativeDirection.CONTINUE;
        boolean roundaboutSimilar = (relativeDirection == RelativeDirection.CIRCLE_CLOCKWISE || relativeDirection == RelativeDirection.CIRCLE_COUNTERCLOCKWISE)
            && (streetEdgeInfo.relativeDirection == RelativeDirection.CIRCLE_CLOCKWISE || streetEdgeInfo.relativeDirection == RelativeDirection.CIRCLE_COUNTERCLOCKWISE);
        return bothSimilar && ((normalSimilar && stayOn && streetEdgeInfo.stayOn) || roundaboutSimilar);


    }

    /**
     * Joins current streetEdgeInfo with provided one
     *
     * This adds together distance and geometry
     * Other things are assumed to be the same since this is called only if
     * {@link #similarTo(StreetEdgeInfo)} returns true on same pair of StreetEdgeInfos
     *
     * It is used to contract StreetEdgeInfos in {@link StreetSegment#compactEdges()}
     * @param streetEdgeInfo
     */
    void add(StreetEdgeInfo streetEdgeInfo) {
        distance+=streetEdgeInfo.distance;

        List<org.locationtech.jts.geom.Coordinate> coordinates = new LinkedList<>();
        Collections.addAll(coordinates, geometry.getCoordinates());

        coordinates.addAll(Arrays.asList(streetEdgeInfo.geometry.getCoordinates()).subList(1, streetEdgeInfo.geometry.getNumPoints())); // Avoid duplications
        org.locationtech.jts.geom.Coordinate[] coordinatesArray = new Coordinate[coordinates.size()];
        //FIXME: copy from list to array
        coordinatesArray = coordinates.toArray(coordinatesArray);
        //FIXME: copy from array to coordinates sequence
        this.geometry = GeometryUtils.geometryFactory.createLineString(coordinatesArray);

    }
}
