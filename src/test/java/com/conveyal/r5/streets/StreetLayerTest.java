package com.conveyal.r5.streets;

import com.conveyal.osmlib.OSM;
import com.conveyal.r5.point_to_point.builder.TNBuilderConfig;
import junit.framework.TestCase;
import org.junit.Test;

import java.util.EnumSet;

public class StreetLayerTest extends TestCase {

    /** Test that subgraphs are removed as expected */
    @Test
    public void testSubgraphRemoval () {
        OSM osm = new OSM(null);
        osm.intersectionDetection = true;
        osm.readFromUrl(StreetLayerTest.class.getResource("subgraph.vex").toString());

        StreetLayer sl = new StreetLayer(TNBuilderConfig.defaultConfig());
        // load from OSM and don't remove floating subgraphs
        sl.loadFromOsm(osm, false, true);

        sl.buildEdgeLists();

        // find an edge that should be removed
        int v = sl.vertexIndexForOsmNode.get(961011556);
        assertEquals(3, sl.incomingEdges.get(v).size());
        assertEquals(3, sl.outgoingEdges.get(v).size());

        // make sure that it's a subgraph
        StreetRouter r = new StreetRouter(sl);
        r.setOrigin(v);
        r.route();
        assertTrue(r.getReachedVertices().size() < 40);

        int e0 = sl.incomingEdges.get(v).get(0);
        int e1 = e0 % 2 == 0 ? e0 + 1 : e0 - 1;

        assertEquals(v, sl.edgeStore.getCursor(e0).getToVertex());
        assertEquals(v, sl.edgeStore.getCursor(e1).getFromVertex());

        sl.removeDisconnectedSubgraphs(40);

        // note: vertices of disconnected subgraphs are not removed
        assertEquals(0, sl.incomingEdges.get(v).size());
        assertEquals(0, sl.outgoingEdges.get(v).size());
    }

    /**
     * Tests if flags, speeds and names are correctly set on split edges
     *
     * @throws Exception
     */
    public void testSplits() throws Exception {
        OSM osm = new OSM(null);
        osm.intersectionDetection = true;
        osm.readFromUrl(StreetLayerTest.class.getResource("speedFlagsTest.pbf").toString());

        StreetLayer streetLayer = new StreetLayer(TNBuilderConfig.defaultConfig());
        streetLayer.loadFromOsm(osm, false, true);
        osm.close();

        //This is needed for inserting new vertices around coordinates
        streetLayer.indexStreets();

        /*
         * In OSM there is one street with forward speed 60 km/h and backward 40
         *
         * It is residential streets and is oneway
         *
         * So permissions backward is pedestrian only and forward ALL.
         *
         * Vertices are: A -> C
         */

        //streetLayer.edgeStore.dump();

        double lat = 46.5558163;
        double lon = 15.6126969;

        EnumSet<EdgeStore.EdgeFlag> forwardEdgeFlags = streetLayer.edgeStore.getCursor(0).getFlags();
        int forwardEdgeSpeed = streetLayer.edgeStore.getCursor(0).getSpeed();
        //String forwardEdgeName = streetLayer.edgeStore.getCursor(0).getName();

        EnumSet<EdgeStore.EdgeFlag> backwardEdgeFlags = streetLayer.edgeStore.getCursor(1).getFlags();
        int backwardEdgeSpeed = streetLayer.edgeStore.getCursor(1).getSpeed();
        //String backwardEdgeName = streetLayer.edgeStore.getCursor(1).getName();

        //This inserts vertex around the middle of the way.
        //Vertices are A->B->C B is new vertex
        int vertexId = streetLayer.getOrCreateVertexNear(lat, lon, 500, true);
        //Edge from A to B
        EdgeStore.Edge oldForwardEdge = streetLayer.edgeStore.getCursor(0);
        //This should always work since in existing edges only length and toVertex changes
        assertEquals(forwardEdgeFlags, oldForwardEdge.getFlags());
        assertEquals(forwardEdgeSpeed, oldForwardEdge.getSpeed());
        //assertEquals(forwardEdgeName, oldForwardEdge.getName());
        //Edge from B to A
        EdgeStore.Edge oldBackwardEdge = streetLayer.edgeStore.getCursor(1);
        assertEquals(backwardEdgeFlags, oldBackwardEdge.getFlags());
        assertEquals(backwardEdgeSpeed, oldBackwardEdge.getSpeed());
        //assertEquals(backwardEdgeName, oldBackwardEdge.getName());

        //Here errors can happen since flags, names, speeds and everything needs to be copied to new edges
        //Edge from B to C
        EdgeStore.Edge newForwardEdge = streetLayer.edgeStore.getCursor(2);
        assertEquals(forwardEdgeFlags, newForwardEdge.getFlags());
        assertEquals(forwardEdgeSpeed, newForwardEdge.getSpeed());
        //assertEquals(forwardEdgeName, newForwardEdge.getName());

        //Edge from C to B
        EdgeStore.Edge newBackwardEdge = streetLayer.edgeStore.getCursor(3);
        assertEquals(backwardEdgeFlags, newBackwardEdge.getFlags());
        assertEquals(backwardEdgeSpeed, newBackwardEdge.getSpeed());
        //assertEquals(backwardEdgeName, newBackwardEdge.getName());

        //streetLayer.edgeStore.dump();
    }
}
