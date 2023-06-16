package com.conveyal.r5.streets;

import com.conveyal.osmlib.OSM;
import com.conveyal.r5.profile.StreetMode;
import com.conveyal.r5.streets.VertexStore.VertexFlag;
import gnu.trove.TIntCollection;
import gnu.trove.iterator.TIntIterator;
import gnu.trove.list.TIntList;
import gnu.trove.list.array.TIntArrayList;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class StreetLayerTest {

    /** Test that subgraphs are removed as expected */
    @Test
    public void testSubgraphRemoval () {
        OSM osm = new OSM(null);
        osm.intersectionDetection = true;
        osm.readFromUrl(StreetLayerTest.class.getResource("subgraph.pbf").toString());

        StreetLayer sl = new StreetLayer();
        // load from OSM and don't remove floating subgraphs
        sl.loadFromOsm(osm, false, true);

        sl.buildEdgeLists();

        // find an edge that should be removed
        int v = sl.vertexIndexForOsmNode.get(961011556);
        assertEquals(3, sl.incomingEdges.get(v).size());
        assertEquals(3, sl.outgoingEdges.get(v).size());

        // make sure that it's a subgraph
        assertTrue(connectedVertices(sl, v) < 40);

        int e0 = sl.incomingEdges.get(v).get(0);
        int e1 = e0 % 2 == 0 ? e0 + 1 : e0 - 1;

        assertEquals(v, sl.edgeStore.getCursor(e0).getToVertex());
        assertEquals(v, sl.edgeStore.getCursor(e1).getFromVertex());

        new TarjanIslandPruner(sl, 40, StreetMode.WALK).run();

        // note: disconnected subgraphs are not removed, they are de-pedestrianized
        assertTrue(sl.flagsAroundVertex(v, EdgeStore.EdgeFlag.ALLOWS_PEDESTRIAN, false));
    }

    /**
     * Tests if flags, speeds and names are correctly set on split edges
     *
     * @throws Exception
     */
    @Test
    public void testSplits() throws Exception {
        OSM osm = new OSM(null);
        osm.intersectionDetection = true;
        osm.readFromUrl(StreetLayerTest.class.getResource("speedFlagsTest.pbf").toString());

        StreetLayer streetLayer = new StreetLayer();
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

    /**
     * Test if edge length and geometry are preserved when an edge is split by stop linking.
     * This test verifies that R5 issue #511 is resolved.
     * TODO also test non-destructive splitting, this is only testing destructive splitting.
     */
     @Test
    public void testSplitsForStops () {
         OSM osm = new OSM(null);
         osm.intersectionDetection = true;

         // One winding road from Oakland, CA that will yield one edge in our street graph
         osm.readFromUrl(StreetLayerTest.class.getResource("snake-rd.pbf").toString());
         // Two coordinates near the edge TODO should these be somewhat far from the edge to test length preservation?
         List<Coordinate> stopCoordinates = new ArrayList<>();
         stopCoordinates.add(new Coordinate(-122.206863, 37.825161));
         stopCoordinates.add(new Coordinate(-122.206751, 37.826258));
         StreetLayer streetLayer = new StreetLayer();
         streetLayer.loadFromOsm(osm, false, true);
         osm.close();
         //This is needed for inserting new vertices around coordinates
         streetLayer.indexStreets();

         // Check that there's only one edge pair in this street layer, composed of two edges in opposite directions.
         assertEquals(streetLayer.edgeStore.nEdges(), 2);
         assertEquals(streetLayer.edgeStore.nEdgePairs(), 1);

         EdgeStore.Edge snakeEdge = streetLayer.edgeStore.getCursor(0);

         long originalOsmId = snakeEdge.getOSMID();
         int originalLength = snakeEdge.getLengthMm();
         Coordinate[] originalGeometry = snakeEdge.getGeometry().getCoordinates();
         int originalFromVertex = snakeEdge.getFromVertex();
         int originalToVertex = snakeEdge.getToVertex();

         TIntList stopVertexIds = new TIntArrayList();
         for (Coordinate stopCoordinate : stopCoordinates) {
             int stopVertexId = streetLayer.createAndLinkVertex(stopCoordinate.y, stopCoordinate.x);
             // Vertex IDs 0 and 1 should be taken by the beginning and end points of the single original edge.
             // Negative vertex ID would indicate a linking problem.
             assertTrue(stopVertexId > 1);
             stopVertexIds.add(stopVertexId);
             // Each added stop should create one new pair of street edges and one new pair of link edges, in addition
             // to the original pair of edges from the original single street.
             assertTrue(streetLayer.edgeStore.nEdgePairs() == stopVertexIds.size() * 2 + 1);
         }

         // As we iterate over all the newly split edges, we'll add up their lengths.
         // The sum should be the same as the original edge.
         int accumulatedForwardLength = 0;
         int accumulatedBackwardLength = 0;

         // Iterate over all edges from 0..N accumulating lengths and sorting geometries.
         Coordinate[][] forwardEdgeGeometries = new Coordinate[3][];
         Coordinate[][] backwardEdgeGeometries = new Coordinate[3][];
         EdgeStore.Edge edge = streetLayer.edgeStore.getCursor();
         int nLinkEdges = 0;
         while(edge.advance()) {
             if (edge.getFlag(EdgeStore.EdgeFlag.LINK)) {
                 nLinkEdges += 1;
                 continue;
             }
             // All newly created non-link edges should represent the same OSM way.
             assertEquals(edge.getOSMID(), originalOsmId);
             int edgeLengthMm = edge.getLengthMm();
             assertTrue(edgeLengthMm > 0);
             Coordinate[] edgeCoordinates = edge.getGeometry().getCoordinates();
             // Make no assumptions about the order of the edges in the edge store.
             // Figure out where it would be in the series of edges made out of the original edge.
             if (edge.isForward()) {
                 accumulatedForwardLength += edgeLengthMm;
                 if (edge.getFromVertex() == originalFromVertex) {
                     forwardEdgeGeometries[0] = edgeCoordinates;
                 } else if (edge.getToVertex() == originalToVertex) {
                     forwardEdgeGeometries[2] = edgeCoordinates;
                 } else {
                     forwardEdgeGeometries[1] = edgeCoordinates;
                 }
             } else {
                 accumulatedBackwardLength += edgeLengthMm;
                 if (edge.getFromVertex() == originalToVertex) {
                     backwardEdgeGeometries[0] = edgeCoordinates;
                 } else if (edge.getToVertex() == originalFromVertex) {
                     backwardEdgeGeometries[2] = edgeCoordinates;
                 } else {
                     backwardEdgeGeometries[1] = edgeCoordinates;
                 }
             }
         }
         assertEquals(nLinkEdges, stopCoordinates.size() * 2);
         // The position of the coordinate currently being compared in the original (unsplit) geometry.
         // Skip first and last coordinates in each edge, which are the edge endpoint vertices.
         // We don't expect to see the split coordinates in the original geometry.
         int originalGeometryIndex = 1;
         for (Coordinate[] edgeCoordinates : forwardEdgeGeometries) {
             assertNotNull(edgeCoordinates);
             assertTrue(edgeCoordinates.length > 2);
             for (int i = 1; i < edgeCoordinates.length - 1; i++){
                 assertTrue(edgeCoordinates[i].equals2D(originalGeometry[originalGeometryIndex]));
                 originalGeometryIndex += 1;
             }
         }
         originalGeometryIndex = originalGeometry.length - 2;
         for (Coordinate[] edgeCoordinates : backwardEdgeGeometries) {
             assertNotNull(edgeCoordinates);
             assertTrue(edgeCoordinates.length > 2);
             for (int i = 1; i < edgeCoordinates.length - 1; i++){
                 assertTrue(edgeCoordinates[i].equals2D(originalGeometry[originalGeometryIndex]));
                 originalGeometryIndex -= 1;
             }
         }
         assertEquals(originalLength, accumulatedForwardLength);
         assertEquals(originalLength, accumulatedBackwardLength);
     }

    /** Test that simple turn restrictions (no via ways) are read properly, using http://www.openstreetmap.org/relation/5696764 */
    @Test
    public void testSimpleTurnRestriction () {
        OSM osm = new OSM(null);
        osm.intersectionDetection = true;
        osm.readFromUrl(StreetLayerTest.class.getResource("cathedral-no-left.pbf").toString());

        StreetLayer sl = new StreetLayer();
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

        StreetLayer sl = new StreetLayer();
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

    /**
     * Test pruning of platforms and edges that are isolated by impassable barrier nodes (e.g., emergency exits).
     * The OSM is based on the Delft railway station, which has four underground platforms (numbered in increasing
     * order east to west). The test fixture uses an area representation for platforms 1 and 3 and a (non-closed)
     * path representation for platforms 2 and 4. The ways for platforms 1 and 2 are connected to the rest of the
     * network by stairways and an elevator.
     * <p>
     * The only connection between platforms 3 and 4 and the rest of the network is an emergency exit at their
     * southern end. So we expect island pruning to clear the ALLOWS_PEDESTRIAN flag for platforms 3 and 4.
     */
    @Test
    public void testBarrierFiltering () {
        OSM osm = new OSM(null);
        osm.intersectionDetection = true;
        osm.readFromUrl(StreetLayerTest.class.getResource("delft-station.pbf").toString());

        StreetLayer sl = new StreetLayer();
        sl.loadFromOsm(osm, true, true);
        sl.buildEdgeLists();

        // Initial assertions about OSM node 337954642 in the test fixture
        // It is an emergency exit, and it is tracked as an intersection
        assertTrue(sl.osm.nodes.get(3377954642L).hasTag("entrance", "emergency"));
        assertTrue(osm.intersectionNodes.contains(3377954642L));
        // It is a node on platforms 3 and 4
        assertEquals(3377954642L, sl.osm.ways.get(899157819L).nodes[10]);
        assertEquals(3377954642L, sl.osm.ways.get(1043558969L).nodes[7]);
        // The entry in vertexIndexForOsmNode map (one of multiple vertices) should be flagged as impassable.
        assertTrue(sl.vertexStore.getFlag(sl.vertexIndexForOsmNode.get(3377954642L), VertexFlag.IMPASSABLE));

        // Check that Platforms 1 and 2 allow pedestrians and are connected to the rest of the network
        int p12v = sl.vertexIndexForOsmNode.get(5303110250L);
        assertTrue(sl.flagsAroundVertex(p12v, EdgeStore.EdgeFlag.ALLOWS_PEDESTRIAN, true));
        assertEquals(68, connectedVertices(sl, p12v));

        // Check that Platforms 3 and 4 have been pruned (ALLOWS_PEDESTRIAN cleared, so no connected vertices)
        int p34v = sl.vertexIndexForOsmNode.get(9604186664L);
        assertTrue(sl.flagsAroundVertex(p34v, EdgeStore.EdgeFlag.ALLOWS_PEDESTRIAN, false));
        assertEquals(0, connectedVertices(sl, p34v));

        // Check that the stairway on the other side of the emergency exit allows pedestrians and is connected to the
        // rest of the network. Actually it contains a barrier=gate node, but it's not tagged as being locked or private
        // so remains traversible.
        int stairsv = sl.vertexIndexForOsmNode.get(5744239458L);
        assertTrue(sl.flagsAroundVertex(stairsv, EdgeStore.EdgeFlag.ALLOWS_PEDESTRIAN, true));
        assertEquals(68, connectedVertices(sl, stairsv));
    }
    
    private int connectedVertices(StreetLayer sl, int vertexId) {
        StreetRouter r = new StreetRouter(sl);
        r.setOrigin(vertexId);
        r.route();
        return r.getReachedVertices().size();
    }

    /**
     * We have decided to tolerate OSM data containing ways that reference missing nodes, because geographic extract
     * processes often produce data like this. Load a file containing a way that ends with some missing nodes
     * and make sure no exception occurs. The input must contain ways creating intersections such that at least one
     * edge is produced, as later steps expect the edge store to be non-empty. The PBF fixture for this test is derived
     * from the hand-tweaked XML file of the same name using osmconvert.
     */
    @Test
    public void testMissingNodes () {
        OSM osm = new OSM(null);
        osm.intersectionDetection = true;
        osm.readFromUrl(StreetLayerTest.class.getResource("missing-nodes.pbf").toString());
        assertDoesNotThrow(() -> {
            StreetLayer sl = new StreetLayer();
            sl.loadFromOsm(osm, true, true);
            sl.buildEdgeLists();
        });
    }

}
