package com.conveyal.osmlib;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;


public class NodeTest {

    public static final double EPSILON = 0.000001;

    @Test
    public void testNode(){
		Node node = new Node();
		assertEquals(node.getLat(), 0.0, EPSILON);
		assertEquals(node.getLon(), 0.0, EPSILON);
		
		node.setLatLon( 47.1, -122.2 );
		assertEquals(node.getLat(), 47.1, EPSILON);
		assertEquals(node.getLon(), -122.2, EPSILON);
		
		Node node2 = new Node(-45.5, 122.2);
		assertEquals(node2.getLat(), -45.5, EPSILON);
		assertEquals(node2.getLon(), 122.2, EPSILON);
	}
}
