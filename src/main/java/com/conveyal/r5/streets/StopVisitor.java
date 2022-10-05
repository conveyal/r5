package com.conveyal.r5.streets;

import gnu.trove.map.TIntIntMap;
import gnu.trove.map.hash.TIntIntHashMap;

/**
 * Saves maxStops number of transitStops that are at least minTravelTimeSeconds from start of search
 * If stop is found multiple times best states according to quantityToMinimize wins.
 */
class StopVisitor implements RoutingVisitor {
    private final int minTravelTimeSeconds;

    private final StreetLayer streetLayer;

    private final RoutingVariable dominanceVariable;

    private final int maxStops;

    private final int NO_STOP_FOUND;

    TIntIntMap stops = new TIntIntHashMap();

    /**
     * @param streetLayer          needed because we need stopForStreetVertex
     * @param dominanceVariable    according to which dominance variable should states be compared (same as in routing)
     * @param maxStops             maximal number of stops that should be found
     * @param minTravelTimeSeconds for stops that should be still added to list of stops
     */
    public StopVisitor(StreetLayer streetLayer, RoutingVariable dominanceVariable,
                       int maxStops, int minTravelTimeSeconds) {
        this.minTravelTimeSeconds = minTravelTimeSeconds;
        this.streetLayer = streetLayer;
        this.dominanceVariable = dominanceVariable;
        this.maxStops = maxStops;
        this.NO_STOP_FOUND = streetLayer.parentNetwork.transitLayer.stopForStreetVertex
                .getNoEntryKey();
    }

    /**
     * If vertex at current state is transit stop. It adds it to best stops
     * if it is more then minTravelTimeSeconds away and is better then existing path
     * to the same stop according to dominance variable
     */
    @Override
    public void visitVertex(RoutingState state) {
        int stop = streetLayer.parentNetwork.transitLayer.stopForStreetVertex.get(state.vertex);
        if (stop != NO_STOP_FOUND) {
            if (state.durationSeconds < minTravelTimeSeconds) {
                return;
            }
            if (!stops.containsKey(stop) || stops.get(stop) > state
                    .getRoutingVariable(dominanceVariable)) {
                stops.put(stop, state.getRoutingVariable(dominanceVariable));
            }
        }

    }

    /**
     * @return true when maxStops transitStops are found
     */
    public boolean shouldBreakSearch() {
        return stops.size() >= this.maxStops;
    }

    /**
     * @return found stops. Same format of returned value as in
     */
    public TIntIntMap getStops() {
        return stops;
    }
}
