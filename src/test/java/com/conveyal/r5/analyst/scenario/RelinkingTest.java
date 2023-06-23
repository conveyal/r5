package com.conveyal.r5.analyst.scenario;

import com.conveyal.gtfs.model.Route;
import com.conveyal.r5.transit.TransportNetwork;
import gnu.trove.map.TIntIntMap;
import gnu.trove.set.TIntSet;
import gnu.trove.set.hash.TIntHashSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static com.conveyal.r5.analyst.scenario.FakeGraph.buildNetwork;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Test that when a modification creates new stops and therefore new street vertices and edges,
 * the transfers, stop trees, and PointSet linkages are updated accordingly. Not only for the newly created stops,
 * but for other stops within walking distance of them.
 *
 * R5 has an optimization that updates only those locations that may have actually changed as a result
 * of the modification, rather than updating all locations.
 */
public class RelinkingTest {

    public TransportNetwork network;
    public long checksum;

    @BeforeEach
    public void setUp () {
        network = buildNetwork(FakeGraph.TransitNetwork.SINGLE_LINE);
        checksum = network.checksum();
    }

}
