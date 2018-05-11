package com.conveyal.r5.edge_server;

import org.junit.Test;

import static com.conveyal.r5.edge_server.EdgeServiceServer.parseLanesTag;
import static org.junit.Assert.*;

public class EdgeServiceServerTest {

    @Test
    public void testParseLanes() {
        assertEquals(1, parseLanesTag("1"));
        assertEquals(2, parseLanesTag("2;4"));
        assertEquals(1, parseLanesTag("1.5"));
        assertEquals(2, parseLanesTag("2; 2; 1; 1; 2"));
    }
}