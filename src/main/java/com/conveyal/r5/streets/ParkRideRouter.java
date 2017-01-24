package com.conveyal.r5.streets;

import com.conveyal.r5.api.util.ParkRideParking;
import com.conveyal.r5.point_to_point.builder.PointToPointQuery;
import com.conveyal.r5.profile.StreetMode;
import com.conveyal.r5.transit.TransitLayer;
import gnu.trove.list.TIntList;
import gnu.trove.map.TIntIntMap;
import gnu.trove.map.TIntObjectMap;
import gnu.trove.map.hash.TIntIntHashMap;
import gnu.trove.map.hash.TIntObjectHashMap;

/**
 * Created by mabu on 24.1.2017.
 */
public class ParkRideRouter extends StreetRouter {

    TIntIntMap transitStopIndexDurationMap;

    TIntIntMap transitStopStreetIndexParkRideIndexMap;

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
        transitStopStreetIndexParkRideIndexMap = new TIntIntHashMap(carParks.size() * 3);
        TIntObjectMap<ParkRideParking> parkRideLocationsMap = streetLayer.parkRideLocationsMap;
        transitStopStreetIndexParkRideState = new TIntObjectHashMap<>(carParks.size());
        final double walkSpeedMillimetersPerSecond = profileRequest.walkSpeed * 1000;

        carParks.forEachValue((state) -> {
            int parkRideStreetVertexIdx = state.vertex;
            int timeToParkRide = state.getDurationSeconds();
            TIntList parkRideTransitStops = parkRideLocationsMap
                .get(parkRideStreetVertexIdx).closestTransfers;
            // for each transit stop reached from this P+R
            for (int i = 0; i < parkRideTransitStops.size(); i += 2) {
                int toStop = parkRideTransitStops.get(i);
                int distanceMillimeters = parkRideTransitStops.get(i + 1);
                int stopStreetVertexIdx = transitLayer.streetVertexForStop.get(toStop);
                int timeToStop = (int) (distanceMillimeters / walkSpeedMillimetersPerSecond);
                int totalTime =
                    timeToParkRide + timeToStop + PointToPointQuery.CAR_PARK_DROPOFF_TIME_S;
                // Adds time to to get to this stop and saves from which P+R we get from STOP
                // if this is the first time we see the stop
                if (!transitStopIndexDurationMap.containsKey(toStop)) {
                    transitStopIndexDurationMap.put(toStop, totalTime);
                    transitStopStreetIndexParkRideIndexMap
                        .put(stopStreetVertexIdx, parkRideStreetVertexIdx);
                    // ELSE we only add time and update P+R if new time is shorter then previously saved one
                } else {
                    int savedTime = transitStopIndexDurationMap.get(toStop);
                    if (totalTime < savedTime) {
                        transitStopIndexDurationMap.put(toStop, totalTime);
                        transitStopStreetIndexParkRideIndexMap
                            .put(stopStreetVertexIdx, parkRideStreetVertexIdx);
                    }
                }

            }

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
     * Calculates path from P+R to requested stopStreetVertex index. If path was already requested it just returns it.
     *
     * @param vertexIndex
     */
    @Override
    public State getStateAtVertex(int vertexIndex) {
        if (this.transitStopStreetIndexParkRideState.containsKey(vertexIndex)) {
            return this.transitStopStreetIndexParkRideState.get(vertexIndex);
        }
        // TODO: different limits?
        StreetRouter walking = new StreetRouter(streetLayer);
        walking.streetMode = StreetMode.WALK;
        walking.profileRequest = profileRequest;
        walking.timeLimitSeconds = profileRequest.maxCarTime * 60;
        walking.dominanceVariable = State.RoutingVariable.DURATION_SECONDS;
        //Which P+R was used to get to this Stop
        int parkRideStreetIndex = this.transitStopStreetIndexParkRideIndexMap.get(vertexIndex);
        walking.setOrigin(parkRideStreetIndex);
        // TODO: setDestination
        walking.route();
        // TODO: We need to add time to switch to CAR PARK somehow
        State state = walking.getStateAtVertex(vertexIndex);
        //TODO: duration is probably only walking time
        if (state != null) {
            this.transitStopStreetIndexParkRideState.put(vertexIndex, state);
        }
        return state;
    }
}
