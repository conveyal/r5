package com.conveyal.r5.streets;

import com.conveyal.r5.point_to_point.builder.TNBuilderConfig;
import com.conveyal.r5.profile.ProfileRequest;
import com.conveyal.r5.profile.StreetMode;
import com.conveyal.r5.profile.StreetPath;
import com.conveyal.r5.transit.TransportNetwork;
import junit.framework.Assert;
import junit.framework.TestCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Created by mabu on 1.3.2016.
 */
public class ReverseRoutingTest extends TestCase {
    private static final Logger LOG = LoggerFactory.getLogger(ReverseRoutingTest.class);
    public StreetLayer streetLayer;
    public TransportNetwork transportNetwork;

    public int A, B, C1, C2, D, E;
    public int AB, BC1, C1D, ED, DC2, C2B;
    List<String> vertexNames;

    @Override
    public void setUp() throws Exception {
        streetLayer = new StreetLayer(new TNBuilderConfig());
        transportNetwork = new TransportNetwork();
        transportNetwork.streetLayer = streetLayer;
        vertexNames = new ArrayList<>(6);

        A = streetLayer.vertexStore.addVertex(41,15);
        B = streetLayer.vertexStore.addVertex(42,15);
        C1 = streetLayer.vertexStore.addVertex(43,12);
        C2 = streetLayer.vertexStore.addVertex(43,10);
        D = streetLayer.vertexStore.addVertex(44,15);
        E = streetLayer.vertexStore.addVertex(45, 15);

        vertexNames.add("A");
        vertexNames.add("B");
        vertexNames.add("C1");
        vertexNames.add("C2");
        vertexNames.add("D");
        vertexNames.add("E");

        //Bidirectional edges
        AB = streetLayer.edgeStore.addStreetPair(A, B, 10000, -1).getEdgeIndex(); //0,1
        ED = streetLayer.edgeStore.addStreetPair(E, D, 13000, -1).getEdgeIndex(); //2,3
        EdgeStore.Edge e = streetLayer.edgeStore.getCursor(0);

        do {
            e.setFlag(EdgeStore.EdgeFlag.ALLOWS_PEDESTRIAN);
            e.setFlag(EdgeStore.EdgeFlag.ALLOWS_BIKE);
            e.setFlag(EdgeStore.EdgeFlag.ALLOWS_CAR);
        } while (e.advance());

        //Single directional edges
        BC1 = streetLayer.edgeStore.addStreetPair(B, C1, 11000, -1).getEdgeIndex();//4,5
        C1D = streetLayer.edgeStore.addStreetPair(C1, D, 12000, -1).getEdgeIndex();//6,7

        DC2 = streetLayer.edgeStore.addStreetPair(D, C2, 12000, -1).getEdgeIndex();//8,9
        C2B = streetLayer.edgeStore.addStreetPair(C2, B, 11000, -1).getEdgeIndex();//10,11

        e = streetLayer.edgeStore.getCursor(BC1);
        do {
            if (e.isForward()){
                e.setFlag(EdgeStore.EdgeFlag.ALLOWS_BIKE);
                e.setFlag(EdgeStore.EdgeFlag.ALLOWS_CAR);
            }
            e.setFlag(EdgeStore.EdgeFlag.ALLOWS_PEDESTRIAN);

        } while (e.advance());

        streetLayer.indexStreets();
        streetLayer.buildEdgeLists();



    }

    public void testReverseRouting() throws Exception {
        LOG.info("Edges:");
        EdgeStore.Edge e = streetLayer.edgeStore.getCursor(0);
        do {
            VertexStore.Vertex fromVertex = streetLayer.vertexStore.getCursor(e.getFromVertex());
            VertexStore.Vertex toVertex = streetLayer.vertexStore.getCursor(e.getToVertex());

            LOG.info("{} -> {} {}", vertexNames.get(e.getFromVertex()),
                 vertexNames.get(e.getToVertex()), e.getFlagsAsString());
        } while (e.advance());

        ProfileRequest profileRequest = new ProfileRequest();
        StreetRouter streetRouter = new StreetRouter(streetLayer);
        streetRouter.profileRequest = profileRequest;
        profileRequest.reverseSearch = true;
        streetRouter.streetMode = StreetMode.BICYCLE;
        VertexStore.Vertex AVertex = streetLayer.vertexStore.getCursor(A);
        VertexStore.Vertex EVertex = streetLayer.vertexStore.getCursor(E);
        Split split = streetLayer.findSplit(AVertex.getLat(), AVertex.getLon(),500, StreetMode.WALK);
        //assertTrue(streetRouter.setDestination(AVertex.getLat(), AVertex.getLon()));
        streetRouter.setOrigin(E); // EVertex.getLat(), EVertex.getLon());
        streetRouter.route();

        StreetRouter.State lastState = streetRouter.getStateAtVertex(A); //streetRouter.getDestinationSplit());
        assertNotNull(lastState);
        StreetPath streetPath = new StreetPath(lastState, transportNetwork, profileRequest.reverseSearch);

        List<Integer> correctEdgeIdx = Arrays.asList( 0, 4, 6, 3 );
        List<Integer> correctDuration = Arrays.asList(3,6,9,13);
        List<Integer> correctDistance = Arrays.asList(10, 21, 33, 46).stream().map(n -> n*1000).collect(
            Collectors.toList());
        List<Long> correctTimes = Arrays.asList(1456876803000L, 1456876806000L, 1456876809000L, 1456876813000L);

        List<Integer> currentEdgeIdx = new ArrayList<>(correctEdgeIdx.size());
        List<Integer> currentDuration = new ArrayList<>(correctDuration.size());
        List<Integer> currentDistance = new ArrayList<>(correctDistance.size());
        for (StreetRouter.State state : streetPath.getStates()) {
            Integer edgeIdx = state.backEdge;
            if (!(edgeIdx == -1 || edgeIdx == null)) {
                EdgeStore.Edge edge = streetLayer.edgeStore.getCursor(edgeIdx);
                LOG.info("Edge IDX:{} {} -> {} {}m IDX:{} {}mm {}sec", edgeIdx, vertexNames.get(edge.getFromVertex()),
                    vertexNames.get(edge.getToVertex()), edge.getLengthM(), state.idx, state.distance, state.durationSeconds);
                currentEdgeIdx.add(edgeIdx);
                currentDuration.add(state.durationSeconds);
                currentDistance.add(state.distance);
            }
        }
        Assert.assertEquals("Correct Edge IDX", correctEdgeIdx, currentEdgeIdx);
        Assert.assertEquals("Correct Distance", correctDistance, currentDistance);
        Assert.assertEquals("Correct duration", correctDuration, currentDuration);
        //Assert.assertEquals("Correct times", correctTimes.stream().map(Instant::ofEpochMilli).toArray(), currectTimes.stream().map(Instant::ofEpochMilli).toArray());
        //Assert.assertEquals("Correct times", correctTimes, currectTimes);

    }
}
