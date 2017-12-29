package com.conveyal.r5.streets;

import com.conveyal.r5.point_to_point.builder.TNBuilderConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Base class for tests of turn costs and restrictions..
 */
public abstract class TurnTest {
    public StreetLayer streetLayer;

    private static final Logger LOG = LoggerFactory.getLogger(TurnTest.class);

    // center vertex index, n/s/e/w vertex indices, n/s/e/w edge indices (always starting from center).
    public int vcenter, vn, vs, ve, vw, vne, vnw, vsw, en, es, ee, ew, ene, enw, esw;

    public void setUp (boolean southernHemisphere) {
        // generate a street layer that looks like this
        // vnw vn
        // |   |
        // |   |/--vne
        // vw--*-- ve
        // |   |
        // vsw vs
        // Edges have the same names (ew, ee, etc), and all start from the central vertex (except enw/vsw which starts at vw)

        double latOffset = southernHemisphere ? -60 : 0;

        streetLayer = new StreetLayer(new TNBuilderConfig());
        vcenter = streetLayer.vertexStore.addVertex(37.363 + latOffset, -122.123);
        vn = streetLayer.vertexStore.addVertex(37.364 + latOffset, -122.123);
        vs = streetLayer.vertexStore.addVertex(37.362 + latOffset, -122.123);
        ve = streetLayer.vertexStore.addVertex(37.363 + latOffset, -122.122);
        vne = streetLayer.vertexStore.addVertex(37.3631 + latOffset, -122.122);
        vw = streetLayer.vertexStore.addVertex(37.363 + latOffset, -122.124);
        vnw = streetLayer.vertexStore.addVertex(37.364 + latOffset, -122.124);

        vsw = streetLayer.vertexStore.addVertex(37.362 + latOffset, -122.124);

        en = streetLayer.edgeStore.addStreetPair(vcenter, vn, 15000, 4).getEdgeIndex();
        ee = streetLayer.edgeStore.addStreetPair(vcenter, ve, 15000, 2).getEdgeIndex();
        es = streetLayer.edgeStore.addStreetPair(vcenter, vs, 15000, 3).getEdgeIndex();
        ew = streetLayer.edgeStore.addStreetPair(vcenter, vw, 15000, 1).getEdgeIndex();
        ene = streetLayer.edgeStore.addStreetPair(vcenter, vne, 15000, 5).getEdgeIndex();
        enw = streetLayer.edgeStore.addStreetPair(vw, vnw, 15000, 6).getEdgeIndex();
        esw = streetLayer.edgeStore.addStreetPair(vw, vsw, 15000, 7).getEdgeIndex();

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
