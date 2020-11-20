package com.conveyal.r5.analyst;

import com.conveyal.r5.OneOriginResult;
import com.conveyal.r5.analyst.cluster.TravelTimeSurfaceTask;
import com.conveyal.r5.transit.TransportNetwork;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Random;
import java.util.stream.Stream;

public class TravelTimeComputerTest {

    public static final int SEED = 1664;

    public Stream<Arguments> testData () {
        Random random = new Random(SEED);
        return random.doubles(1000, 5, 100).mapToObj(d -> Arguments.of(d, d*2));
    }

    @ParameterizedTest
    @MethodSource("testData")
    public void testBlah () {
        TravelTimeSurfaceTask task = null;
        TransportNetwork network = null;
        TravelTimeComputer computer = new TravelTimeComputer(task, network);
        OneOriginResult oneOriginResult = computer.computeTravelTimes();
        // assertNormallyDistributed(median, stddev, values);
    }

    public void assertNormallyDistributed(double median, double stddev, double[] values) {

    }

    public TravelTimeSurfaceTask makeSinglePointTask () {
        TravelTimeSurfaceTask task = new TravelTimeSurfaceTask();
        return null;
    }

    public PointSet makeFreeformPointSet () {
        return null;
    }

    // methods to make networks and modifications
    // make routes that are frequency based and scheduled
    // travel times should be normally distributed around accessTime + (ride + 1/2 headway)
    // make street grid exactly aligned with supplied web mercator cells
    // or not... use freeform pointsets.
    // what methods already exist for programmatically generating test fixtures?

    // static methods returning bounding rectangles WgsTestEnvelopes.location
    // programmatically generate OSM:
    // -- two roads along north and south of HK island
    // -- from GeoJSON so we can create and display it in QGIS
    // -- pure synthetic, exact distances in the desert
    // folder structure, files and generative grouped by location
    // testNetwork interface: getEnvelope, getOSM, getGtfs
    // methods to load from GeoJSON or CSV, generate on grids
}
