package com.conveyal.r5.streets;

import com.conveyal.osmlib.OSM;
import com.conveyal.r5.point_to_point.builder.TNBuilderConfig;
import com.conveyal.r5.profile.StreetMode;
import gnu.trove.TIntCollection;
import gnu.trove.iterator.TIntIterator;
import junit.framework.TestCase;
import org.junit.Test;

import java.util.Arrays;
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

        new TarjanIslandPruner(sl, 40, StreetMode.WALK).run();

        // note: disconnected subgraphs are not removed, they are de-pedestrianized
        final EdgeStore.Edge edge = sl.edgeStore.getCursor();
        assertTrue(Arrays.stream(sl.incomingEdges.get(v).toArray())
                .noneMatch(i -> sl.edgeStore.getCursor(i).getFlag(EdgeStore.EdgeFlag.ALLOWS_PEDESTRIAN)));
        assertTrue(Arrays.stream(sl.outgoingEdges.get(v).toArray())
                .noneMatch(i -> sl.edgeStore.getCursor(i).getFlag(EdgeStore.EdgeFlag.ALLOWS_PEDESTRIAN)));
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
        int vertexId = streetLayer.getOrCreateVertexNear(lat, lon, StreetMode.WALK);
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

    /** Test that simple turn restrictions (no via ways) are read properly, using http://www.openstreetmap.org/relation/5696764 */
    @Test
    public void testSimpleTurnRestriction () {
        OSM osm = new OSM(null);
        osm.intersectionDetection = true;
        osm.readFromUrl(StreetLayerTest.class.getResource("cathedral-no-left.pbf").toString());

        StreetLayer sl = new StreetLayer(TNBuilderConfig.defaultConfig());
        // load from OSM and don't remove floating subgraphs
        sl.loadFromOsm(osm, false, true);

        sl.buildEdgeLists();

        // locate the turn restriction from northbound Connecticut Ave NW onto westbound Cathedral Ave NW (in the District of Columbia)
        int v = sl.vertexIndexForOsmNode.get(49815553L);
        int e = -1;
        EdgeStore.Edge edge = sl.edgeStore.getCursor();

        for (TIntIterator it = sl.incomingEdges.get(v).iterator(); it.hasNext();) {
            e = it.next();
            edge.seek(e);
            // Connecticut Ave NW south of the intersection
            if (edge.getOSMID() == 382852845L) break;
        }

        assertTrue(e != -1);

        // make sure it's in the turn restrictions
        assertTrue(sl.edgeStore.turnRestrictions.containsKey(e));

        TIntCollection restrictions = sl.edgeStore.turnRestrictions.get(e);

        assertEquals(1, restrictions.size());

        TurnRestriction restriction = sl.turnRestrictions.get(restrictions.iterator().next());

        assertEquals(e, restriction.fromEdge);
        assertEquals(0, restriction.viaEdges.length);
        edge.seek(restriction.toEdge);
        // Cathedral Ave NW, west of Connecticut.
        assertEquals(130908001L, edge.getOSMID());
        assertFalse(restriction.only);
    }

    /** Test that complex turn restrictions (via ways) are read properly, using http://www.openstreetmap.org/relation/555630 */
    @Test
    public void testComplexTurnRestriction () {
        OSM osm = new OSM(null);
        osm.intersectionDetection = true;
        osm.readFromUrl(StreetLayerTest.class.getResource("reisterstown-via-restriction.pbf").toString());

        StreetLayer sl = new StreetLayer(TNBuilderConfig.defaultConfig());
        // load from OSM and don't remove floating subgraphs
        sl.loadFromOsm(osm, false, true);

        sl.buildEdgeLists();

        // Node at the start of U-turn restriction on Reisterstown Road just south of the Baltimore Beltway in Pikesville, MD, USA
        int v = sl.vertexIndexForOsmNode.get(2460634038L);
        int e = -1;
        EdgeStore.Edge edge = sl.edgeStore.getCursor();

        for (TIntIterator it = sl.incomingEdges.get(v).iterator(); it.hasNext();) {
            e = it.next();
            edge.seek(e);
            // Little bit of Reisterstown Rd. in intersection
            if (edge.getOSMID() == 238215855) break;
        }

        assertTrue(e != -1);

        // make sure it's in the turn restrictions
        assertTrue(sl.edgeStore.turnRestrictions.containsKey(e));

        TIntCollection restrictions = sl.edgeStore.turnRestrictions.get(e);

        assertEquals(2, restrictions.size());

        // annoyingly there are two turn restrictions at that node. Find the one we care about.

        TurnRestriction restriction = null;
        for (TIntIterator it = restrictions.iterator(); it.hasNext();) {
            restriction = sl.turnRestrictions.get(it.next());
            if (restriction.viaEdges.length > 0) break; // we have found the complex one
        }

        assertNotNull(restriction);

        assertEquals(e, restriction.fromEdge);
        assertEquals(2, restriction.viaEdges.length);

        // check the funny little bits of Reisterstown in the intersection.
        // make sure also that they wind up in the correct order.
        edge.seek(restriction.viaEdges[0]);
        assertEquals(238215854L, edge.getOSMID());

        edge.seek(restriction.viaEdges[1]);
        assertEquals(53332280L, edge.getOSMID());

        edge.seek(restriction.toEdge);
        // Bit of Reisterstown in intersection but after restriction
        assertEquals(238215856L, edge.getOSMID());
        assertFalse(restriction.only);
    }
}
