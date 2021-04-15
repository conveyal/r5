package com.conveyal.r5.analyst.scenario;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import com.conveyal.r5.streets.VertexStore;
import com.conveyal.r5.transit.TransportNetwork;

import org.junit.jupiter.api.Test;

import java.util.stream.IntStream;

/** Test that checksums are stable between serializations of the graph. */
public class ChecksumTest {
    @Test
    public void testChecksumStability() {
        TransportNetwork network = FakeGraph.buildNetwork(FakeGraph.TransitNetwork.BIDIRECTIONAL);
        long checksum = network.checksum();

        // do a second checksum without doing anything to the network
        long checksum2 = network.checksum();

        assertEquals(checksum, checksum2, "checksum not stable under repeated serialization");

        // traverse a lot of stuff in the network and see if checksums change
        IntStream.range(0, network.transitLayer.tripPatterns.size())
                .forEach(i -> network.transitLayer.tripPatterns.get(i));
        IntStream.range(0, network.streetLayer.vertexStore.getVertexCount())
                .forEach(
                        i ->
                                network.streetLayer
                                        .vertexStore
                                        .getCursor(i)
                                        .getFlag(VertexStore.VertexFlag.TRAFFIC_SIGNAL));
        network.transitLayer.stopForStreetVertex.forEachKey(
                i -> {
                    network.transitLayer.stopForStreetVertex.get(i);
                    return true;
                });

        long checksumTraverse = network.checksum();
        assertEquals(
                checksum,
                checksumTraverse,
                "checksum not stable after traversing collections/maps");

        // change something
        network.transitLayer.tripPatterns.get(0).routeIndex = -153;

        long changedChecksum = network.checksum();

        assertNotEquals(checksum, changedChecksum, "Changing network did not change checksum");
    }
}
