package com.conveyal.r5.streets;

import com.conveyal.r5.analyst.scenario.FakeGraph;
import com.conveyal.r5.point_to_point.builder.TNBuilderConfig;
import com.conveyal.r5.profile.ProfileRequest;
import com.conveyal.r5.profile.StreetMode;
import com.conveyal.r5.transit.TransportNetwork;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;

/**
 * Created by mabu on 30.6.2017.
 */
public class SpeedsTests {

    private static final Logger LOG = LoggerFactory.getLogger(SpeedsTests.class);



    private TransportNetwork transportNetwork;

    @Before
    public void setUp() throws Exception {
        transportNetwork = TransportNetwork.fromFiles(FakeGraph.class.getResource("columbus.osm.pbf").getFile(),
            new ArrayList<>(), TNBuilderConfig.defaultConfig());
    }

    @Test
    public void testSpeed() throws Exception {
        EdgeStore.Edge testEdge = getEdgeFromOSM(37024879); //maxspeed =35 mph


        double EXPECTED_SPEED_KMH = 56.327; //56.304001
        double EXPECTED_SPEED_MS = 15.6464; //15.64
        double EXPECTED_CAR_SPEED_MMS = 15.6464; //15.64

        compareSpeed(testEdge, EXPECTED_SPEED_KMH, EXPECTED_SPEED_MS, EXPECTED_CAR_SPEED_MMS);

        testEdge = getEdgeFromOSM(21409508); //highway = residential
        compareSpeed(testEdge, 40.24, 11.18, 11.18);

        testEdge = getEdgeFromOSM(21346162); //highway = motorway
        compareSpeed(testEdge, 88.48, 24.58, 24.58);

        testEdge = getEdgeFromOSM(158779252); //highway = footway
        compareSpeed(testEdge, 40.24, 11.18, 11.18);

    }

    @Test
    public void testTimes() throws Exception {


        ProfileRequest pr = new ProfileRequest();
        pr.fromLat = 40.0975849;
        pr.fromLon = -83.0137315;
        pr.toLat = 40.095727;
        pr.toLon = -83.0145031;

        compareTime(pr, StreetMode.CAR, 54, 88, 253580);
        compareTime(pr, StreetMode.WALK, 196, 561, 253580);
        compareTime(pr, StreetMode.BICYCLE, 64, 162, 253580);

        pr.toLat = 39.9972981;
        pr.toLon = -83.0115556;
        compareTime(pr, StreetMode.CAR, 823, 719, 11500909);
        compareTime(pr, StreetMode.WALK, 8752, 17569, 11276153);
        compareTime(pr, StreetMode.BICYCLE, 2947, 2888, 11463477);
    }

    private void compareTime(ProfileRequest pr, StreetMode streetMode,
        int expectedDuration, int expectedWeight, int expectedDistance) {
        StreetRouter sr = new StreetRouter(transportNetwork.streetLayer);
        sr.profileRequest = pr;
        sr.streetMode = streetMode;
        sr.distanceLimitMeters = 100_000;
        Assert.assertTrue(sr.setDestination(pr.toLat, pr.toLon));
        Assert.assertTrue(sr.setOrigin(pr.fromLat, pr.fromLon));

        sr.route();

        StreetRouter.State lastState = sr.getState(sr.getDestinationSplit());
        Assert.assertNotNull("Last state NULL for "+ streetMode, lastState);

        //LOG.info("State:{}", lastState);
        Assert.assertEquals(expectedDuration, lastState.getDurationSeconds());
        Assert.assertEquals(expectedWeight, lastState.weight);
        Assert.assertEquals(expectedDistance, lastState.distance);
    }

    private void compareSpeed(EdgeStore.Edge testEdge, double EXPECTED_SPEED_KMH,
        double EXPECTED_SPEED_MS, double EXPECTED_CAR_SPEED_MMS) {
        //LOG.info("Edge:{} {}", testEdge, testEdge.getCarSpeedMetersPerSecond());

        Assert.assertEquals(EXPECTED_SPEED_KMH,testEdge.getSpeedkmh(),0.1);
        Assert.assertEquals(EXPECTED_SPEED_MS,testEdge.getCarSpeedMetersPerSecond(),0.1);
        final ProfileRequest pr = new ProfileRequest();

        Assert.assertEquals(EXPECTED_CAR_SPEED_MMS,testEdge.calculateSpeed(pr, StreetMode.CAR),0.1);
        Assert.assertEquals(pr.bikeSpeed,testEdge.calculateSpeed(pr, StreetMode.BICYCLE),0.1);
        Assert.assertEquals(pr.walkSpeed,testEdge.calculateSpeed(pr, StreetMode.WALK),0.1);
    }

    private EdgeStore.Edge getEdgeFromOSM(long osmid) {
        EdgeStore.Edge edge = transportNetwork.streetLayer.edgeStore.getCursor();
        while(edge.advance()) {
            if (edge.getOSMID() == osmid) {
                return edge;
            }
        }
        return null;

    }
}
