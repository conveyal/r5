package com.conveyal.r5.analyst.network.scene;

import com.conveyal.r5.analyst.FreeFormPointSet;
import com.conveyal.r5.analyst.OnDemandAccess;
import com.conveyal.r5.profile.StreetMode;
import com.conveyal.r5.streets.StreetRouter;
import com.conveyal.r5.transit.TransportNetwork;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;

import java.util.List;

import static com.conveyal.r5.analyst.network.scene.SceneRouting.NOON;
import static com.conveyal.r5.analyst.network.scene.SceneRouting.onDemand;
import static com.conveyal.r5.analyst.network.scene.SceneRouting.vertexAt;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests evaluating travel time to destination points off the street network via on-demand
/// services, exercising both the car adn walk aspects of [OnDemandAccess]. For car, the vehicle
/// continues along the split edge for the destination to drop the rider at their door, clipped by
/// the service's drop-off place filter. For walk, the rider walks onward from state where they
/// alighted from the vehicle, with no such clipping. Points are evaluated through the same method
/// used by TravelTimeComputer, using small free-form destination point sets.
public class OnDemandDestinationTest {

    /// Route on the given service from the given origin and evaluate the given destination points,
    /// expressed as scene coordinate pairs.
    private static OnDemandAccess evaluate (
          TransportNetwork network, Scene scene, double x, double y, String serviceId, double[]... xy) {
        Coordinate[] coordinates = new Coordinate[xy.length];
        for (int i = 0; i < xy.length; i++) {
            coordinates[i] = new Coordinate(scene.lonForXY(xy[i][0], xy[i][1]), scene.latForY(xy[i][1]));
        }
        FreeFormPointSet destinations = new FreeFormPointSet(coordinates);
        StreetRouter walk = SceneRouting.route(network, scene, x, y, StreetMode.WALK);
        return OnDemandAccess.route(walk, List.of(onDemand(network, serviceId)), NOON, destinations);
    }

    /// A destination beside the vertex-free middle of Long Rd, inside the drop-off zone. The
    /// vehicle delivers the rider to their door. The time is the ride to the nearest in-zone vertex
    /// plus a partial edge at riding pace, far less than walking 400 meters back from that vertex.
    @Test
    void midEdgeDestinationInZone () {
        Scene scene = new Scene();
        TransportNetwork network = OnDemandZoneAccessTest.twoVillagesNetwork(scene);
        OnDemandAccess flex = evaluate(network, scene, 1000, 30, "village", new double[] {2000, 10});
        int atVertex = flex.egressRouter.getTravelTimeToVertex(vertexAt(network, scene, 2400, 0));
        int atDoor = flex.directTimes.getTravelTimeToPoint(0);
        assertTrue(atDoor >= atVertex,
            "Door delivery extends the ride, so it cannot beat the time at the vertex itself.");
        assertTrue(atDoor < atVertex + 120,
            "The 400 meter partial edge should be traversed at driving speed, but the door took "
                + (atDoor - atVertex) + " seconds beyond the vertex.");
    }

    /// A destination on the same Long Rd edge just outside the drop-off zone boundary. The edge it
    /// splits ends at an in-zone vertex holding a ride state, but the drop-off filter rejects the
    /// point, so it is reached by walking from that vertex. Without that clipping, driving speed
    /// would leak one block past every zone boundary.
    @Test
    void destinationJustOutsideZone () {
        Scene scene = new Scene();
        TransportNetwork network = OnDemandZoneAccessTest.twoVillagesNetwork(scene);
        OnDemandAccess flex = evaluate(network, scene, 1000, 30, "village", new double[] {1500, 10});
        int atVertex = flex.egressRouter.getTravelTimeToVertex(vertexAt(network, scene, 2400, 0));
        int outside = flex.directTimes.getTravelTimeToPoint(0);
        assertNotEquals(Integer.MAX_VALUE, outside,
            "Walking onward from the drop-off should still reach a point outside the zone.");
        assertTrue(outside >= atVertex + 500,
            "The 900 meters beyond the zone edge must be walked, not driven, but the point was "
                + "reached " + (outside - atVertex) + " seconds after the vertex.");
    }

    /// A destination a short way along the street from a curb stop's meeting area. Drop-off at
    /// a location group is interpreted as drop-off anywhere in the meeting area (here using the
    /// smaller discovery area for curbside stops). The edge the destination links to ends at the
    /// single vertex in that meeting area, so the vehicle continues to the door at driving speed.
    @Test
    void destinationNearCurbStop () {
        Scene scene = new Scene();
        TransportNetwork network = OnDemandStopAccessTest.drivableStreetNetwork(scene, 10);
        OnDemandAccess flex = evaluate(network, scene, 1000, 10, "flexIn", new double[] {560, -10});
        int atCurb = flex.egressRouter.getTravelTimeToVertex(vertexAt(network, scene, 500, 0));
        int atDoor = flex.directTimes.getTravelTimeToPoint(0);
        assertTrue(atDoor >= atCurb,
            "Door delivery extends the ride, so it cannot beat the time at the curb vertex.");
        assertTrue(atDoor < atCurb + 60,
            "The 60 meter partial edge should be priced at riding pace, but the door took "
                + (atDoor - atCurb) + " seconds beyond the curb.");
    }

    /// A destination on a side street that does not touch the curb stop's meeting area. No flex
    /// driving state reaches it, so it is reached by the egress walk from the curb, at walking pace
    /// over a series of street edges.
    @Test
    void destinationOffMeetingArea () {
        Scene scene = new Scene();
        TransportNetwork network = OnDemandStopAccessTest.drivableStreetNetwork(scene, 10);
        OnDemandAccess flex = evaluate(network, scene, 1000, 10, "flexIn", new double[] {740, 190});
        int atCurb = flex.egressRouter.getTravelTimeToVertex(vertexAt(network, scene, 500, 0));
        int offArea = flex.directTimes.getTravelTimeToPoint(0);
        assertNotEquals(Integer.MAX_VALUE, offArea,
            "Walking onward from the curb should reach the side street.");
        assertTrue(offArea >= atCurb + 250,
            "The 440 meter street path from the curb must be walked, but the side street point "
                + "was reached " + (offArea - atCurb) + " seconds after the curb.");
    }

}
