package com.conveyal.r5.labeling;

import com.conveyal.osmlib.Way;
import com.conveyal.r5.streets.EdgeStore;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.Assert.*;

/**
 * Created by mabu on 26.11.2015.
 */
public class TraversalPermissionLabelerTest {

    static TraversalPermissionLabeler traversalPermissionLabeler;

    public static final EnumSet<EdgeStore.EdgeFlag> ALL = EnumSet
        .of(EdgeStore.EdgeFlag.ALLOWS_BIKE, EdgeStore.EdgeFlag.ALLOWS_CAR,
            EdgeStore.EdgeFlag.ALLOWS_PEDESTRIAN);
    public static final EnumSet<EdgeStore.EdgeFlag> PEDESTRIAN_AND_BICYCLE = EnumSet.of(
        EdgeStore.EdgeFlag.ALLOWS_PEDESTRIAN, EdgeStore.EdgeFlag.ALLOWS_BIKE);
    public static final EnumSet<EdgeStore.EdgeFlag> PEDESTRIAN_AND_CAR = EnumSet.of(
        EdgeStore.EdgeFlag.ALLOWS_PEDESTRIAN, EdgeStore.EdgeFlag.ALLOWS_CAR );
    public static final EnumSet<EdgeStore.EdgeFlag> BICYCLE_AND_CAR = EnumSet.of(EdgeStore.EdgeFlag.ALLOWS_BIKE,
        EdgeStore.EdgeFlag.ALLOWS_CAR);
    public static final EnumSet<EdgeStore.EdgeFlag> NONE = EnumSet.noneOf(EdgeStore.EdgeFlag.class);

    public static final EnumSet<EdgeStore.EdgeFlag> PEDESTRIAN = EnumSet.of(EdgeStore.EdgeFlag.ALLOWS_PEDESTRIAN);

    public static final EnumSet<EdgeStore.EdgeFlag> BICYCLE = EnumSet.of(EdgeStore.EdgeFlag.ALLOWS_BIKE);

    @BeforeClass
    public static void setUpClass() {
        traversalPermissionLabeler = new USTraversalPermissionLabeler();
    }

    @Test
    public void testCyclewayPermissions() throws Exception {
        Way osmWay = new Way();
        osmWay.addTag("highway", "cycleway");
        EnumSet<EdgeStore.EdgeFlag> forwardPermissions = traversalPermissionLabeler.getPermissions(osmWay, false);
        EnumSet<EdgeStore.EdgeFlag> backwardPermissions = traversalPermissionLabeler.getPermissions(osmWay, true);

        Set<EdgeStore.EdgeFlag> forwardFiltered = filterFlags(forwardPermissions);
        Set<EdgeStore.EdgeFlag> backwardFiltered = filterFlags(backwardPermissions);

        assertEquals(PEDESTRIAN_AND_BICYCLE, forwardFiltered);
        assertEquals(PEDESTRIAN_AND_BICYCLE, backwardFiltered);


        osmWay.addTag("access", "destination");
        forwardPermissions = traversalPermissionLabeler.getPermissions(osmWay, false);
        backwardPermissions = traversalPermissionLabeler.getPermissions(osmWay, true);

        forwardFiltered = filterFlags(forwardPermissions);
        backwardFiltered = filterFlags(backwardPermissions);

        //CAR is destination
        assertEquals(ALL, forwardFiltered);
        assertEquals(ALL, backwardFiltered);

    }

    @Test
    public void testOnewayPermissions() {
        Way osmWay = new Way();
        osmWay.addTag("highway", "residential");
        osmWay.addTag("oneway", "true");
        osmWay.addTag("oneway:bicycle", "no");

        EnumSet<EdgeStore.EdgeFlag> forwardPermissions = traversalPermissionLabeler.getPermissions(osmWay, false);
        EnumSet<EdgeStore.EdgeFlag> backwardPermissions = traversalPermissionLabeler.getPermissions(osmWay, true);

        Set<EdgeStore.EdgeFlag> forwardFiltered = filterFlags(forwardPermissions);
        Set<EdgeStore.EdgeFlag> backwardFiltered = filterFlags(backwardPermissions);

        assertEquals(ALL, forwardFiltered);
        assertEquals(PEDESTRIAN_AND_BICYCLE, backwardFiltered);
    }

    @Test
    public void testPath() throws Exception {
        Way osmWay = makeOSMWayFromTags("highway=path;access=private");

        EnumSet<EdgeStore.EdgeFlag> forwardPermissions = traversalPermissionLabeler.getPermissions(osmWay, false);
        EnumSet<EdgeStore.EdgeFlag> backwardPermissions = traversalPermissionLabeler.getPermissions(osmWay, true);

        Set<EdgeStore.EdgeFlag> forwardFiltered = filterFlags(forwardPermissions);
        Set<EdgeStore.EdgeFlag> backwardFiltered = filterFlags(backwardPermissions);

        assertEquals(NONE, forwardFiltered);
        assertEquals(NONE, backwardFiltered);
    }

    @Test
    public void testPlatform() throws Exception {
        Way osmWay = makeOSMWayFromTags("highway=platform;public_transport=platform");

        EnumSet<EdgeStore.EdgeFlag> forwardPermissions = traversalPermissionLabeler.getPermissions(osmWay, false);
        EnumSet<EdgeStore.EdgeFlag> backwardPermissions = traversalPermissionLabeler.getPermissions(osmWay, true);

        Set<EdgeStore.EdgeFlag> forwardFiltered = filterFlags(forwardPermissions);
        Set<EdgeStore.EdgeFlag> backwardFiltered = filterFlags(backwardPermissions);

        assertEquals(PEDESTRIAN, forwardFiltered);
        assertEquals(PEDESTRIAN, backwardFiltered);
    }

    @Test
    public void testSidewalk() throws Exception {
        Way osmWay = new Way();
        osmWay.addTag("highway", "footway");
        EnumSet<EdgeStore.EdgeFlag> forwardPermissions = traversalPermissionLabeler.getPermissions(osmWay, false);
        EnumSet<EdgeStore.EdgeFlag> backwardPermissions = traversalPermissionLabeler.getPermissions(osmWay, true);

        Set<EdgeStore.EdgeFlag> forwardFiltered = filterFlags(forwardPermissions);
        Set<EdgeStore.EdgeFlag> backwardFiltered = filterFlags(backwardPermissions);

        assertEquals(PEDESTRIAN_AND_BICYCLE, forwardFiltered);
        assertEquals(PEDESTRIAN_AND_BICYCLE, backwardFiltered);

        osmWay = makeOSMWayFromTags("footway=sidewalk;highway=footway");

        forwardPermissions = traversalPermissionLabeler.getPermissions(osmWay, false);
        backwardPermissions = traversalPermissionLabeler.getPermissions(osmWay, true);

        forwardFiltered = filterFlags(forwardPermissions);
        backwardFiltered = filterFlags(backwardPermissions);

        assertEquals(PEDESTRIAN, forwardFiltered);
        assertEquals(PEDESTRIAN, backwardFiltered);
    }

    @Test
    public void testSteps() throws Exception {
        Way osmWay = makeOSMWayFromTags("highway=steps");
        EnumSet<EdgeStore.EdgeFlag> forwardPermissions = traversalPermissionLabeler.getPermissions(osmWay, false);
        EnumSet<EdgeStore.EdgeFlag> backwardPermissions = traversalPermissionLabeler.getPermissions(osmWay, true);

        Set<EdgeStore.EdgeFlag> forwardFiltered = filterFlags(forwardPermissions);
        Set<EdgeStore.EdgeFlag> backwardFiltered = filterFlags(backwardPermissions);

        assertEquals(PEDESTRIAN, forwardFiltered);
        assertEquals(PEDESTRIAN, backwardFiltered);
    }

    /**
     * Removes all flags except permissions
     * @param permissions
     * @return
     */
    private static Set<EdgeStore.EdgeFlag> filterFlags(EnumSet<EdgeStore.EdgeFlag> permissions) {
        return permissions.stream()
            .filter(ALL::contains)
            .collect(Collectors.toSet());
    }

    /**
     * Creates osmway based on provided tags
     *
     * For example: footway=sidewalk;highway=footway
     * This adds two tags footway=sidewalk and highway=footway. Order doesn't matter.
     * @param tags with tags separated with ; and tag and value separated with =
     * @return
     */
    private static Way makeOSMWayFromTags(String tags) {
        Way osmWay = new Way();
        String[] pairs = tags.split(";");
        for (String pair : pairs) {
            String[] kv = pair.split("=");
            osmWay.addTag(kv[0], kv[1]);
        }
        return osmWay;
    }
}