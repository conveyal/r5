package com.conveyal.r5.streets;

import com.conveyal.r5.point_to_point.builder.TNBuilderConfig;
import junit.framework.TestCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Base class for tests of turn costs and restrictions..
 */
public abstract class TurnTest {
    public StreetLayer streetLayer;

    private static final Logger LOG = LoggerFactory.getLogger(TurnTest.class);

    // center vertex index, n/s/e/w vertex indices, n/s/e/w edge indices (always starting from center).
    // FIXME capital letters should only be used for constants
    public int VCENTER, VN, VS, VE, VW, VNE, VNW, VSW, EN, ES, EE, EW, ENE, ENW, ESW;

    public void setUp (boolean southernHemisphere) {
        // generate a street layer that looks like this
        // VNW VN
        // |   |
        // |   |/--VNE
        // VW--*-- VE
        // |   |
        // VSW VS
        // Edges have the same names (EW, EE, etc), and all start from the central vertex (except ENW/VSW which starts at VW)

        double latOffset = southernHemisphere ? -60 : 0;

        streetLayer = new StreetLayer(new TNBuilderConfig());
        VCENTER = streetLayer.vertexStore.addVertex(37.363 + latOffset, -122.123);
        VN = streetLayer.vertexStore.addVertex(37.364 + latOffset, -122.123);
        VS = streetLayer.vertexStore.addVertex(37.362 + latOffset, -122.123);
        VE = streetLayer.vertexStore.addVertex(37.363 + latOffset, -122.122);
        VNE = streetLayer.vertexStore.addVertex(37.3631 + latOffset, -122.122);
        VW = streetLayer.vertexStore.addVertex(37.363 + latOffset, -122.124);
        VNW = streetLayer.vertexStore.addVertex(37.364 + latOffset, -122.124);

        VSW = streetLayer.vertexStore.addVertex(37.362 + latOffset, -122.124);

        EN = streetLayer.edgeStore.addStreetPair(VCENTER, VN, 15000, 4).getEdgeIndex();
        EE = streetLayer.edgeStore.addStreetPair(VCENTER, VE, 15000, 2).getEdgeIndex();
        ES = streetLayer.edgeStore.addStreetPair(VCENTER, VS, 15000, 3).getEdgeIndex();
        EW = streetLayer.edgeStore.addStreetPair(VCENTER, VW, 15000, 1).getEdgeIndex();
        ENE = streetLayer.edgeStore.addStreetPair(VCENTER, VNE, 15000, 5).getEdgeIndex();
        ENW = streetLayer.edgeStore.addStreetPair(VW, VNW, 15000, 6).getEdgeIndex();
        ESW = streetLayer.edgeStore.addStreetPair(VW, VSW, 15000, 7).getEdgeIndex();

        EdgeStore.Edge e = streetLayer.edgeStore.getCursor(0);

        do {
            e.setFlag(EdgeStore.EdgeFlag.ALLOWS_PEDESTRIAN);
            e.setFlag(EdgeStore.EdgeFlag.ALLOWS_CAR);
            e.setFlag(EdgeStore.EdgeFlag.LINKABLE);
        } while (e.advance());

        streetLayer.indexStreets();
        streetLayer.buildEdgeLists();
    }

    /** create a turn restriction */
    public void restrictTurn (boolean onlyTurn, int from, int to, int... via) {
        TurnRestriction restriction = new TurnRestriction();
        restriction.fromEdge = from;
        restriction.toEdge = to;
        restriction.only = onlyTurn;
        restriction.viaEdges = via;
        LOG.debug("{}", restriction);
        int ridx = streetLayer.turnRestrictions.size();
        streetLayer.turnRestrictions.add(restriction);
        streetLayer.edgeStore.turnRestrictions.put(restriction.fromEdge, ridx);
        streetLayer.addReverseTurnRestriction(restriction, ridx);


    }

    /**
     * Creates turn restriction without adding it to turnRestriction maps
     *
     * Used to create expected turn restrictions
     */
    static TurnRestriction makeTurnRestriction(boolean onlyTurn, int from, int to) {
        TurnRestriction turnRestriction = new TurnRestriction();
        turnRestriction.fromEdge = from;
        turnRestriction.toEdge = to;
        turnRestriction.only = onlyTurn;
        return turnRestriction;
    }

    /**
     * Creates turn restriction without adding it to turnRestriction maps
     *
     * Used to create expected turn restrictions
     */
    static TurnRestriction makeTurnRestriction(boolean onlyTurn, int from, int to,
        int viaEdge) {
        TurnRestriction turnRestriction = new TurnRestriction();
        turnRestriction.fromEdge = from;
        turnRestriction.toEdge = to;
        turnRestriction.only = onlyTurn;
        turnRestriction.viaEdges = new int[]{viaEdge};
        return  turnRestriction;
    }
}
