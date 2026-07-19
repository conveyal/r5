package com.conveyal.r5.analyst.network.scene;

import com.conveyal.gtfs.flex.MeetingAreas;
import com.conveyal.gtfs.flex.OnDemand;
import com.conveyal.gtfs.flex.OnDemandPlaceFilter;
import com.conveyal.r5.profile.StreetMode;
import com.conveyal.r5.streets.Split;
import com.conveyal.r5.transit.TransportNetwork;
import gnu.trove.map.TIntIntMap;
import org.junit.jupiter.api.Test;

import static com.conveyal.r5.analyst.network.scene.SceneRouting.onDemand;
import static com.conveyal.r5.analyst.network.scene.SceneRouting.stopIndex;
import static com.conveyal.r5.analyst.network.scene.SceneRouting.stopVertex;
import static com.conveyal.r5.analyst.network.scene.SceneRouting.vertexAt;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests of MeetingArea discovery and associated place filtering predicates. A stop's meeting area
/// is the set of drivable-edge vertices within walking distance of the stop, discovered over the
/// walking network. These tests reuse the scenes for [OnDemandStopAccessTest], whose geometry was
/// designed to discriminate exactly these cases.
public class MeetingAreaTest {

    /// The station scene puts an elevated stop directly above a motorway with a frontage road
    /// 20 meters away across a barrier, while the legitimate access point is Access Road, a
    /// 310 meter walk through the station plaza and a footpath. The meeting area must be found
    /// by walking, so it contains the Access Road vertices, prices them at walked distance,
    /// and excludes the frontage road that a straight-line association would wrongly choose.
    @Test
    void barrierSeparatedStation () {
        Scene scene = new Scene();
        TransportNetwork network = OnDemandStopAccessTest.stationNetwork(scene);
        TIntIntMap area = network.meetingAreas().areaWithDistances(stopIndex(network, "elevated"));
        int accessPath = vertexAt(network, scene, 200, 110);
        assertTrue(area.containsKey(accessPath),
            "The area should contain the Access Road vertex nearest the station on foot.");
        assertEquals(310_000, area.get(accessPath), 15_000,
            "The retained distance should be the 310 meter walk over link, plaza and footpath.");
        assertTrue(area.containsKey(vertexAt(network, scene, 200, 0)),
            "The area should extend down Access Road to Main St, a 420 meter walk.");
        assertFalse(area.containsKey(vertexAt(network, scene, 400, 110)),
            "Walkable vertices without any drivable edge (the footpath-plaza junction) are not meeting points.");
        assertFalse(area.containsKey(vertexAt(network, scene, 0, 80)),
            "The frontage road is 20 meters away across a barrier and must not be in the area.");
        assertFalse(area.containsKey(vertexAt(network, scene, 1000, 0)),
            "The Main St junction with the frontage road is a 1220 meter walk, past the budget.");
        assertFalse(area.containsKey(vertexAt(network, scene, 0, 100)),
            "The motorway has no pedestrian connection at all.");
    }

    /// A stop with no drivable street within the walking budget has an empty meeting area
    /// (also logged as a data quality warning), while its walk link continues to work.
    @Test
    void isolatedStop () {
        Scene scene = new Scene();
        TransportNetwork network = OnDemandStopAccessTest.islandNetwork(scene);
        TIntIntMap area = network.meetingAreas().areaWithDistances(stopIndex(network, "island"));
        assertTrue(area.isEmpty(),
            "A stop with no drivable street in walking reach should have an empty meeting area.");
        assertTrue(stopVertex(network, "island") >= 0,
            "The stop should still be walk-linked to its island.");
    }

    /// For a stop on the curb, intended specifically for on-demand service, the meeting area should
    /// be exact (the curb is the stop). The vertex where the stop's walk link meets the street is
    /// within the meeting area.
    @Test
    void curbStop () {
        Scene scene = new Scene();
        TransportNetwork network = OnDemandStopAccessTest.drivableStreetNetwork(scene, 10);
        TIntIntMap area = network.meetingAreas().areaWithDistances(stopIndex(network, "curb"));
        int curb = vertexAt(network, scene, 500, 0);
        assertTrue(area.containsKey(curb), "The street vertex below the stop should be in the area.");
        assertEquals(10_000, area.get(curb), 3_000,
            "The curb vertex should be priced at the 10 meter link walk.");
    }

    /// The place filters constructed for a service must agree with the kind of pick-up and drop-off
    /// endpoints the service specifies. Pick-up at location_groups (sets of stops) is subject to
    /// vertices being members of a certain set, and drop-off at a polygon is subject to geometric
    /// point containment in a polygon. The polygon side can pre-select candidate edges from the spatial index.
    @Test
    void serviceEndpointFilters () {
        Scene scene = new Scene();
        TransportNetwork network = OnDemandStopAccessTest.stationNetwork(scene);
        OnDemand od = onDemand(network, "taxiOut");
        OnDemandPlaceFilter pickUp = OnDemandPlaceFilter.pickUp(od, network);
        assertTrue(pickUp.containsVertex(vertexAt(network, scene, 200, 110)),
            "The pick-up filter should accept states at meeting area vertices.");
        assertFalse(pickUp.containsVertex(vertexAt(network, scene, 0, 80)),
            "The pick-up filter should reject states on the barrier-separated frontage road.");
        assertNull(pickUp.clipCandidateEdges(),
            "Meeting areas scan states directly rather than pre-selecting candidate edges.");
        OnDemandPlaceFilter dropOff = OnDemandPlaceFilter.dropOff(od, network);
        assertTrue(dropOff.containsVertex(vertexAt(network, scene, 2000, 0)),
            "The drop-off filter should accept states inside the zone polygon.");
        assertFalse(dropOff.containsVertex(vertexAt(network, scene, 1000, 0)),
            "The drop-off filter should reject states outside the zone polygon.");
        assertNotNull(dropOff.clipCandidateEdges(),
            "The polygon clip should pre-select candidate edges from the spatial index.");
    }

    /// A point filter for a meeting area tests the split connecting an off-road point
    /// to the streets. A point whose nearest drivable edge leads into the area should be accepted,
    /// while a point beside the barrier-separated frontage road should not, even though it is
    /// closer to the stop as the crow flies.
    @Test
    void pointGateAtBarrier () {
        Scene scene = new Scene();
        TransportNetwork network = OnDemandStopAccessTest.stationNetwork(scene);
        OnDemandPlaceFilter pickUp = OnDemandPlaceFilter.pickUp(onDemand(network, "taxiOut"), network);
        double accessLat = scene.latForY(50);
        double accessLon = scene.lonForXY(210, 50);
        Split accessSplit = network.streetLayer.findSplit(accessLat, accessLon, 100, StreetMode.CAR);
        assertNotNull(accessSplit, "A point beside Access Road should split a drivable edge.");
        assertTrue(pickUp.containsPoint(accessLat, accessLon, accessSplit),
            "A point on an Access Road edge between area vertices should pass the gate.");
        double frontageLat = scene.latForY(79);
        double frontageLon = scene.lonForXY(500, 79);
        Split frontageSplit = network.streetLayer.findSplit(frontageLat, frontageLon, 100, StreetMode.CAR);
        assertNotNull(frontageSplit, "A point beside the frontage road should split a drivable edge.");
        assertFalse(pickUp.containsPoint(frontageLat, frontageLon, frontageSplit),
            "A point on the frontage road should fail the gate despite being near the stop.");
    }

    /// The radius specified for meeting areas must be large enough to encompass the stop complex
    /// described in the test scene.
    @Test
    void areaRadiusConstant () {
        assertTrue(MeetingAreas.MEETING_AREA_RADIUS_METERS >= 400,
            "The area radius must cover a station whose legitimate curb is a 310 meter walk.");
    }

}
