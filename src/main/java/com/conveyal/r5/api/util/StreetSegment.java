package com.conveyal.r5.api.util;

import com.conveyal.r5.common.DirectionUtils;
import com.conveyal.r5.common.GeometryUtils;
import com.conveyal.r5.profile.StreetPath;
import com.conveyal.r5.streets.EdgeStore;
import com.conveyal.r5.streets.StreetLayer;
import com.conveyal.r5.streets.StreetRouter;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.LineString;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;

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

    //For Jackson reading used in StreetSegmentTest
    public StreetSegment() {
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
        List<org.locationtech.jts.geom.Coordinate> coordinates = new LinkedList<>();

        for (Integer edgeIdx : path.getEdges()) {
            EdgeStore.Edge edge = path.getEdge(edgeIdx);
            LineString geometry = edge.getGeometry();

            if (geometry != null) {
                if (coordinates.size() == 0) {
                    Collections.addAll(coordinates, geometry.getCoordinates());
                } else {
                    Coordinate last = coordinates.get(coordinates.size()-1);
                    Coordinate first = geometry.getCoordinateN(0);
                    if (!last.equals2D(first)) {
                            System.err.println("Last and first coordinate differ!:" + last + "!=" + first);
                        }
                    coordinates.addAll(Arrays.asList(geometry.getCoordinates()).subList(1, geometry.getNumPoints())); // Avoid duplications
                }
            }
        }
        //Used to know if found bike rental station where we picked a bike or one where we dropped of a bike
        boolean first = true;
        double lastAngleRad = 0;
        for (StreetRouter.State state: path.getStates()) {
            int edgeIdx = state.backEdge;
            if (edgeIdx >= 0) {
                EdgeStore.Edge edge = path.getEdge(edgeIdx);
                StreetEdgeInfo streetEdgeInfo = new StreetEdgeInfo();
                streetEdgeInfo.edgeId = edgeIdx;
                streetEdgeInfo.geometry = edge.getGeometry();
                streetEdgeInfo.streetName = streetLayer.getNameEdgeIdx(edgeIdx, Locale.ENGLISH);
                //TODO: decide between NonTransitMode and mode
                streetEdgeInfo.mode = NonTransitMode.valueOf(state.streetMode.toString());
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
                if (mode == LegMode.CAR_PARK && streetLayer.parkRideLocationsMap != null &&
                    streetLayer.parkRideLocationsMap.get(state.vertex) != null) {
                    streetEdgeInfo.parkRide = streetLayer.parkRideLocationsMap.get(state.vertex);
                }

                double thisAngleRad = DirectionUtils.getFirstAngle(streetEdgeInfo.geometry);
                if (streetEdges.isEmpty()) {
                    streetEdgeInfo.setAbsoluteDirection(thisAngleRad);
                    streetEdgeInfo.relativeDirection = RelativeDirection.DEPART;
                } else {
                    streetEdgeInfo.setDirections(Math.toDegrees(lastAngleRad), Math.toDegrees(thisAngleRad), edge.getFlag(
                        EdgeStore.EdgeFlag.ROUNDABOUT));
                    //If we are moving on street with same name we need to set stayOn to true
                    StreetEdgeInfo prev = streetEdges.get(streetEdges.size()-1);
                    if (prev.streetName != null && prev.streetName.equals(streetEdgeInfo.streetName)) {
                        streetEdgeInfo.stayOn = true;
                    }
                }
                lastAngleRad = DirectionUtils.getLastAngle(streetEdgeInfo.geometry);
                streetEdges.add(streetEdgeInfo);
            }
        }
        //This joins consecutive streetEdgeInfos with CONTINUE Relative direction and same street name to one StreetEdgeInfo
        compactEdges(); //TODO: this needs to be optional because of profile routing and edgeIDs
        Coordinate[] coordinatesArray = new Coordinate[coordinates.size()];
        //FIXME: copy from list to array
        coordinatesArray = coordinates.toArray(coordinatesArray);
        //FIXME: copy from array to coordinates sequence
        this.geometry = GeometryUtils.geometryFactory.createLineString(coordinatesArray);
        //This is not read from state because this is requested mode which is not always the same as state mode
        //For example bicycle plan can consist of bicycle and walk modes if walking the bike is required
        this.mode = mode;
    }

    /**
     * This joins consecutive streetEdgeInfos with CONTINUE
     * Relative direction and same street name to one StreetEdgeInfo
     *
     * Similar edges are found with {@link StreetEdgeInfo#similarTo(StreetEdgeInfo)} and joined with {@link StreetEdgeInfo#add(StreetEdgeInfo)}
     */
    void compactEdges() {
        if (streetEdges.size() < 2) {
            return;
        }
        List<StreetEdgeInfo> newEdges = new ArrayList<>(streetEdges.size());
        StreetEdgeInfo prev = streetEdges.get(0);
        for (int i=1; i < streetEdges.size(); i++) {
            StreetEdgeInfo current = streetEdges.get(i);
            if (prev.similarTo(current)) {
                prev.add(current);
            } else {
                newEdges.add(prev);
                prev = current;
            }
        }
        newEdges.add(prev);
        streetEdges = newEdges;

    }
}
