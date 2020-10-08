package com.conveyal.r5.streets;

import com.conveyal.osmlib.Way;
import com.conveyal.r5.profile.ProfileRequest;
import com.conveyal.r5.profile.StreetMode;

import static com.conveyal.r5.streets.LaDotCostTags.Direction.BACKWARD;
import static com.conveyal.r5.streets.LaDotCostTags.Direction.FORWARD;

/**
 * Groups together SingleModeTraversalTimes for walking and biking.
 */
public class EdgeTraversalTimes implements TraversalTimeCalculator {

    private final SingleModeTraversalTimes walkTraversalTimes;
    private final SingleModeTraversalTimes bikeTraversalTimes;

    public EdgeTraversalTimes (EdgeStore edgeStore) {
        this.walkTraversalTimes = new SingleModeTraversalTimes(edgeStore);
        this.bikeTraversalTimes = new SingleModeTraversalTimes(edgeStore);
    }

    @Override
    public int traversalTimeSeconds (EdgeStore.Edge currentEdge, StreetMode streetMode, ProfileRequest req) {
        if (streetMode == StreetMode.WALK) {
            return walkTraversalTimes.traversalTimeSeconds(currentEdge, req.walkSpeed);
        } else if (streetMode == StreetMode.BICYCLE) {
            return bikeTraversalTimes.traversalTimeSeconds(currentEdge, req.bikeSpeed);
        } else { // CAR
            return (int)(currentEdge.getLengthM() / currentEdge.getCarSpeedMetersPerSecond());
        }
    }

    @Override
    public int turnTimeSeconds (int fromEdge, int toEdge, StreetMode streetMode) {
        if (streetMode == StreetMode.WALK) {
            return walkTraversalTimes.turnTimeSeconds(fromEdge, toEdge);
        } else if (streetMode == StreetMode.BICYCLE) {
            return bikeTraversalTimes.turnTimeSeconds(fromEdge, toEdge);
        } else { // CAR - TODO fall back on basic cost calculator for turns.
            return 0;
        }
    }

    public void setEdgePair (int forwardEdge, Way way) {
        int backwardEdge = forwardEdge + 1;
        LaDotCostTags forwardTags = new LaDotCostTags(way, FORWARD);
        walkTraversalTimes.setOneEdge(forwardEdge, new LaDotWalkCostSupplier(forwardTags));
        bikeTraversalTimes.setOneEdge(forwardEdge, new LaDotBikeCostSupplier(forwardTags));
        LaDotCostTags backwardTags = new LaDotCostTags(way, BACKWARD);
        walkTraversalTimes.setOneEdge(backwardEdge, new LaDotWalkCostSupplier(backwardTags));
        bikeTraversalTimes.setOneEdge(backwardEdge, new LaDotBikeCostSupplier(backwardTags));
    }

    public void summarize () {
        walkTraversalTimes.summarize("Walk");
        bikeTraversalTimes.summarize("Bike");
    }

    private EdgeTraversalTimes (SingleModeTraversalTimes walkTraversalTimes, SingleModeTraversalTimes bikeTraversalTimes) {
        this.walkTraversalTimes = walkTraversalTimes;
        this.bikeTraversalTimes = bikeTraversalTimes;
    }

    public EdgeTraversalTimes extendOnlyCopy (EdgeStore edgeStore) {
        return new EdgeTraversalTimes(
                walkTraversalTimes.extendOnlyCopy(edgeStore),
                bikeTraversalTimes.extendOnlyCopy(edgeStore)
        );
    }

    /**
     * Copy all traversal time characteristics of one edge to another.
     * For use only on scenario copies, could be moved into standard edge replication code like copyPairFlagsAndSpeeds.
     * @param walkFactor if null, copy from oldEdge, otherwise set to walkFactor
     * @param bikeFactor if null, copy from oldEdge, otherwise set to bikeFactor
     */
    public void copyTimes (int oldEdge, int newEdge, Double walkFactor, Double bikeFactor) {
        walkTraversalTimes.copyTimes(oldEdge, newEdge, walkFactor);
        bikeTraversalTimes.copyTimes(oldEdge, newEdge, bikeFactor);
    }

    // Stopgap to pad out the traversal times when adding new edges
    public void addOneEdge () {
        walkTraversalTimes.setOneEdge();
        bikeTraversalTimes.setOneEdge();
    }

    public void setWalkTimeFactor (int edgeIndex, double walkTimeFactor) {
        walkTraversalTimes.perceivedLengthMultipliers.set(edgeIndex, walkTimeFactor);
    }

    public void setBikeTimeFactor (int edgeIndex, double bikeTimeFactor) {
        bikeTraversalTimes.perceivedLengthMultipliers.set(edgeIndex, bikeTimeFactor);
    }
}
