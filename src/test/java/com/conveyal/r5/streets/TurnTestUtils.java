package com.conveyal.r5.streets;

/**
 * Moved from TurnTest
 */
public class TurnTestUtils {
    public StreetLayer streetLayer;

    /** create a turn restriction */
    public void restrictTurn (boolean onlyTurn, int from, int to, int... via) {
        TurnRestriction restriction = new TurnRestriction();
        restriction.fromEdge = from;
        restriction.toEdge = to;
        restriction.only = onlyTurn;
        restriction.viaEdges = via;
        int ridx = streetLayer.turnRestrictions.size();
        streetLayer.turnRestrictions.add(restriction);
        streetLayer.edgeStore.turnRestrictions.put(restriction.fromEdge, ridx);
    }
}
