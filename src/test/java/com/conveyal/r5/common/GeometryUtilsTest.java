package com.conveyal.r5.common;

import org.junit.jupiter.api.Test;

import static com.conveyal.r5.common.GeometryUtils.turfDistance;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class GeometryUtilsTest {

    /// Check the results of turfDistance against hard-coded values produced by Turf itself in JS.
    /// The pairs cover travel along both axes, near the equator and at mid-latitudes.
    @Test
    public void turfReferenceValues () {
        assertEquals(111190.846158, turfDistance(0.5, -79.0, 0.5, -78.0), 1e-5,
                "east-west near the equator");
        assertEquals(111195.080234, turfDistance(0.5, -79.0, 1.5, -79.0), 1e-5,
                "north-south near the equator");
        assertEquals(13429.643884, turfDistance(37.7749, -122.4194, 37.8044, -122.2712), 1e-5,
                "mid-latitude");
        assertEquals(823.491991, turfDistance(48.8566, 2.3522, 48.8600, 2.3622), 1e-5,
                "short hop of typical inter-stop length");
    }

}
