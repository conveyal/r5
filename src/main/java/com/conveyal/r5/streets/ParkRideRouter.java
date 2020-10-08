package com.conveyal.r5.streets;

import com.conveyal.r5.api.util.ParkRideParking;
import com.conveyal.r5.point_to_point.builder.PointToPointQuery;
import com.conveyal.r5.transit.TransitLayer;
import gnu.trove.map.TIntIntMap;
import gnu.trove.map.TIntObjectMap;
import gnu.trove.map.hash.TIntIntHashMap;
import gnu.trove.map.hash.TIntObjectHashMap;

/**
 * Created by mabu on 24.1.2017.
 */
public class ParkRideRouter extends StreetRouter {

    //Key is stop index, value is duration to reach it for getReachedStops
    TIntIntMap transitStopIndexDurationMap;
    //Key is streetVertex of stops, value is duration to reach it for getTravelTimeToVertex
    TIntIntMap streetVertexDurationMap;

    //Key is streetVertex value is state from park ride to this stop for making path
    TIntObjectMap<State> transitStopStreetIndexParkRideState;

    public ParkRideRouter(StreetLayer streetLayer) {
        super(streetLayer);
    }

    /**
     * From map of found P+Rs creates:
     * - map of transitStopIndex duration which is used in getReachedStops for raptor transit routing
     * - map of streetVertexTransitStop P+R - to know from which P+R we got to this stop
     * <p>
     * If stop appears multiple times, stop with shortest access time (time to get to P+R + switch time + to stop) is saved
     *
     * @param carParks
     * @param transitLayer Used to get StreetVertex from stopIndex
     */
    public void addParks(TIntObjectMap<State> carParks, TransitLayer transitLayer) {
        transitStopIndexDurationMap = new TIntIntHashMap(carParks.size() * 3);
        streetVertexDurationMap = new TIntIntHashMap(carParks.size() * 3);
        TIntObjectMap<ParkRideParking> parkRideLocationsMap = streetLayer.parkRideLocationsMap;
        transitStopStreetIndexParkRideState = new TIntObjectHashMap<>(carParks.size());
        final double walkSpeedMillimetersPerSecond = profileRequest.walkSpeed * 1000;

        carParks.forEachValue((state) -> {
            int parkRideStreetVertexIdx = state.vertex;
            int timeToParkRide = state.getDurationSeconds();
            TIntObjectMap<State> parkRideTransitStops = parkRideLocationsMap
                .get(parkRideStreetVertexIdx).closestTransfers;
            // for each transit stop reached from this P+R
            parkRideTransitStops.forEachEntry((toStop, pr_state) ->  {
                int distanceMillimeters = pr_state.distance;
                int stopStreetVertexIdx = transitLayer.streetVertexForStop.get(toStop);
                int timeToStop = (int) (distanceMillimeters / walkSpeedMillimetersPerSecond);
                int totalTime =
                    timeToParkRide + timeToStop + PointToPointQuery.CAR_PARK_DROPOFF_TIME_S;
                // Adds time to to get to this stop and saves from which P+R we get from STOP
                // if this is the first time we see the stop
                if (!transitStopIndexDurationMap.containsKey(toStop)) {
                    transitStopIndexDurationMap.put(toStop, totalTime);
                    transitStopStreetIndexParkRideState.put(stopStreetVertexIdx, pr_state);
                    streetVertexDurationMap.put(stopStreetVertexIdx, totalTime);
                    // ELSE we only add time and update P+R if new time is shorter then previously saved one
                } else {
                    int savedTime = transitStopIndexDurationMap.get(toStop);
                    if (totalTime < savedTime) {
                        transitStopIndexDurationMap.put(toStop, totalTime);
                        transitStopStreetIndexParkRideState.put(stopStreetVertexIdx, pr_state);
                        streetVertexDurationMap.put(stopStreetVertexIdx, totalTime);
                    }
                }

                return true;
            });

            return true;
        });
    }

    /**
     * This uses stops found in {@link StopVisitor} if transitStopSearch is true
     * and DOESN'T search in found states for stops
     *
     * @return a map from transit stop indexes to their distances from the origin (or whatever the dominance variable is).
     * Note that the TransitLayer contains all the information about which street vertices are transit stops.
     */
    @Override
    public TIntIntMap getReachedStops() {
        //stop index map P+R time + walk time
        return transitStopIndexDurationMap;
    }

    /**
     * Get a single best state at a vertex. NB this should not be used for propagating to samples, as you need to apply
     * turn costs/restrictions during propagation.
     * <p>
     * Gets path from P+R to requested stop. (Path was found during graph build stage)
     *
     * @param vertexIndex
     */
    @Override
    public State getStateAtVertex(int vertexIndex) {
        //TODO: calculate correct distance and duration since currently only walk part has distance and duration added
        if (this.transitStopStreetIndexParkRideState.containsKey(vertexIndex)) {
            return this.transitStopStreetIndexParkRideState.get(vertexIndex);
        } else {
            return null;
        }
    }

    /**
     * Returns travel time to this vertex. Only returns time to stops, since only those times are saved
     * @param vertexIndex
     * @return
     */
    @Override
    public int getTravelTimeToVertex(int vertexIndex) {
        if (this.streetVertexDurationMap.containsKey(vertexIndex)) {
            return streetVertexDurationMap.get(vertexIndex);
        } else {
            return Integer.MAX_VALUE;
        }
    }
}
