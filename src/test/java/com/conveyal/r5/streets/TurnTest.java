package com.conveyal.r5.streets;

import com.conveyal.r5.point_to_point.builder.TNBuilderConfig;
import junit.framework.TestCase;

/**
 * Base class for tests of turn costs and restrictions..
 */
public abstract class TurnTest extends TurnTestUtils {

    // center vertex index, n/s/e/w vertex indices, n/s/e/w edge indices (always starting from center).
    public int VCENTER, VN, VS, VE, VW, VNE, VNW, EN, ES, EE, EW, ENE, ENW;

    public void setUp (boolean southernHemisphere) {
        // generate a street layer that looks like this
        // VNW VN
        // |   |
        // |   |/--VNE
        // VW--*-- VE
        //     |
        //     VS
        // Edges have the same names (EW, EE, etc), and all start from the central vertex (except ENW which starts at VW)

        double latOffset = southernHemisphere ? -60 : 0;

        streetLayer = new StreetLayer(new TNBuilderConfig());
        VCENTER = streetLayer.vertexStore.addVertex(37.363 + latOffset, -122.123);
        VN = streetLayer.vertexStore.addVertex(37.364 + latOffset, -122.123);
        VS = streetLayer.vertexStore.addVertex(37.362 + latOffset, -122.123);
        VE = streetLayer.vertexStore.addVertex(37.363 + latOffset, -122.122);
        VNE = streetLayer.vertexStore.addVertex(37.3631 + latOffset, -122.122);
        VW = streetLayer.vertexStore.addVertex(37.363 + latOffset, -122.124);
        VNW = streetLayer.vertexStore.addVertex(37.364 + latOffset, -122.124);

        EN = streetLayer.edgeStore.addStreetPair(VCENTER, VN, 15000, 4).getEdgeIndex();
        EE = streetLayer.edgeStore.addStreetPair(VCENTER, VE, 15000, 2).getEdgeIndex();
        ES = streetLayer.edgeStore.addStreetPair(VCENTER, VS, 15000, 3).getEdgeIndex();
        EW = streetLayer.edgeStore.addStreetPair(VCENTER, VW, 15000, 1).getEdgeIndex();
        ENE = streetLayer.edgeStore.addStreetPair(VCENTER, VNE, 15000, 5).getEdgeIndex();
        ENW = streetLayer.edgeStore.addStreetPair(VW, VNW, 15000, 6).getEdgeIndex();

        EdgeStore.Edge e = streetLayer.edgeStore.getCursor(0);

        do {
            e.setFlag(EdgeStore.EdgeFlag.ALLOWS_PEDESTRIAN);
            e.setFlag(EdgeStore.EdgeFlag.ALLOWS_CAR);
        } while (e.advance());

        streetLayer.indexStreets();
        streetLayer.buildEdgeLists();
    }

}
