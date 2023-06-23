package com.conveyal.r5.analyst.network;

import com.conveyal.r5.OneOriginResult;
import com.conveyal.r5.analyst.TravelTimeComputer;
import com.conveyal.r5.analyst.cluster.AnalysisWorkerTask;
import com.conveyal.r5.analyst.cluster.PathResult;
import com.conveyal.r5.analyst.cluster.TimeGridWriter;
import com.conveyal.r5.transit.TransportNetwork;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.CoordinateXY;

import java.io.FileOutputStream;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * This is a collection of tests using roads and transit lines laid out in a large perfect grid in the desert.
 * These networks have very predictable travel times, so instead of just checking that results don't change from one
 * version to the next of R5 (a form of snapshot testing) this checks that they match theoretically expected travel
 * times given headways, transfer points, distances, common trunks and competing lines, etc.
 *
 * TODO Option to align street grid exactly with sample points in WebMercatorGridPointSet to eliminate walking time
 *      between origin and destination and transit stops or street intersections. Also check splitting.
 */
public class SimpsonDesertTests {

    public static final Coordinate SIMPSON_DESERT_CORNER = new CoordinateXY(136.5, -25.5);

}
