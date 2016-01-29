package com.conveyal.r5.api.util;

import com.conveyal.r5.streets.StreetLayer;
import com.conveyal.r5.common.DirectionUtils;
import com.vividsolutions.jts.geom.LineString;
import com.conveyal.r5.common.GeometryUtils;
import com.conveyal.r5.profile.StreetPath;
import com.conveyal.r5.streets.EdgeStore;
import com.conveyal.r5.streets.StreetRouter;
import com.vividsolutions.jts.geom.Coordinate;

import java.util.*;

/**
 * A response object describing a non-transit part of an Option. This is either an access/egress leg of a transit
 * trip, or a direct path to the destination that does not use transit.
 */
public class StreetSegment {
    //Which mode of transport is used @notnull
    public LegMode mode;
    //Time in seconds for this part of trip @notnull
    public int duration;
    //Distance in mm for this part of a trip @notnull
    public int distance;
    //TODO: geometry needs to be split when there is mode switch. Probably best to use indexes in geometry
    //Geometry of all the edges
    public LineString geometry;
    public List<StreetEdgeInfo> streetEdges;
    //List of elevation elements each elevation has a distance (from start of this segment) and elevation at this point (in meters)
    public List<Elevation> elevation;
    public List<Alert> alerts;

    @Override public String toString() {
        return "\tStreetSegment{" +
            "mode='" + mode + '\'' +
            ", time=" + duration +
            ", streetEdges=" + streetEdges.size() +
            '}' + "\n";
    }

    /**
     * creates StreetSegment from path
     *
     * It fills geometry fields and duration for now.
     * @param path
     * @param mode requested mode for this path
     * @param streetLayer
     */
    public StreetSegment(StreetPath path, LegMode mode, StreetLayer streetLayer) {
        duration = path.getDuration();
        distance = path.getDistance();
        streetEdges = new LinkedList<>();
        List<com.vividsolutions.jts.geom.Coordinate> coordinates = new LinkedList<>();

        for (Integer edgeIdx : path.getEdges()) {
            EdgeStore.Edge edge = path.getEdge(edgeIdx);
            LineString geometry = edge.getGeometry();

            if (geometry != null) {
                if (coordinates.size() == 0) {
                    Collections.addAll(coordinates, geometry.getCoordinates());
                } else {
                    coordinates.addAll(Arrays.asList(geometry.getCoordinates()).subList(1, geometry.getNumPoints())); // Avoid duplications
                }
            }
        }
        //Used to know if found bike rental station where we picked a bike or one where we dropped of a bike
        boolean first = true;
        for (StreetRouter.State state: path.getStates()) {
            int edgeIdx = state.backEdge;
            double lastAngle = 0;
            if (edgeIdx != -1) {
                EdgeStore.Edge edge = path.getEdge(edgeIdx);
                StreetEdgeInfo streetEdgeInfo = new StreetEdgeInfo();
                streetEdgeInfo.edgeId = edgeIdx;
                streetEdgeInfo.geometry = edge.getGeometry();
                streetEdgeInfo.streetName = streetLayer.getNameEdgeIdx(edgeIdx, Locale.ENGLISH);
                //TODO: decide between NonTransitMode and mode
                streetEdgeInfo.mode = NonTransitMode.valueOf(state.mode.toString());
                streetEdgeInfo.distance = edge.getLengthMm();
                //Adds bikeRentalStation to streetEdgeInfo
                if (state.isBikeShare && streetLayer != null && streetLayer.bikeRentalStationMap != null) {
                    BikeRentalStation bikeRentalStation = streetLayer.bikeRentalStationMap.get(state.vertex);
                    if (bikeRentalStation != null) {
                        if (first) {
                            streetEdgeInfo.bikeRentalOnStation = bikeRentalStation;
                            first = false;
                        } else {
                            streetEdgeInfo.bikeRentalOffStation = bikeRentalStation;
                        }
                    }
                }

                double thisAngle = DirectionUtils.getFirstAngle(streetEdgeInfo.geometry);
                if (streetEdges.isEmpty()) {
                    streetEdgeInfo.setAbsoluteDirection(thisAngle);
                    streetEdgeInfo.relativeDirection = RelativeDirection.DEPART;
                } else {
                    streetEdgeInfo.setDirections(lastAngle, thisAngle, false);
                }
                lastAngle = DirectionUtils.getLastAngle(streetEdgeInfo.geometry);
                streetEdges.add(streetEdgeInfo);
            }
        }
        Coordinate[] coordinatesArray = new Coordinate[coordinates.size()];
        //FIXME: copy from list to array
        coordinatesArray = coordinates.toArray(coordinatesArray);
        //FIXME: copy from array to coordinates sequence
        this.geometry = GeometryUtils.geometryFactory.createLineString(coordinatesArray);
        //This is not read from state because this is requested mode which is not always the same as state mode
        //For example bicycle plan can consist of bicycle and walk modes if walking the bike is required
        this.mode = mode;
    }
}
