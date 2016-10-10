package com.conveyal.r5.analyst.scenario;

import com.conveyal.r5.streets.VertexStore;
import com.conveyal.r5.transit.TransportNetwork;
import org.junit.Test;

import java.util.stream.IntStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

/**
 * Test that checksums are stable between serializations of the graph.
 */
public class ChecksumTest {
    @Test
    public void testChecksumStability () {
        TransportNetwork network = FakeGraph.buildNetwork(FakeGraph.TransitNetwork.BIDIRECTIONAL);
        long checksum = network.checksum();

        // do a second checksum without doing anything to the network
        long checksum2 = network.checksum();

        assertEquals("checksum not stable under repeated serialization", checksum, checksum2);

        // traverse a lot of stuff in the network and see if checksums change
        IntStream.range(0, network.transitLayer.tripPatterns.size()).forEach(i -> network.transitLayer.tripPatterns.get(i));
        IntStream.range(0, network.streetLayer.vertexStore.getVertexCount())
                .forEach(i -> network.streetLayer.vertexStore.getCursor(i).getFlag(VertexStore.VertexFlag.TRAFFIC_SIGNAL));
        network.transitLayer.stopForStreetVertex.forEachKey(i -> {
            network.transitLayer.stopForStreetVertex.get(i);
            return true;
        });

        long checksumTraverse = network.checksum();
        assertEquals("checksum not stable after traversing collections/maps", checksum, checksumTraverse);

        // change something
        network.transitLayer.tripPatterns.get(0).routeIndex = -153;

        long changedChecksum = network.checksum();

        assertNotEquals("Changing network did not change checksum", checksum, changedChecksum);
    }
}
