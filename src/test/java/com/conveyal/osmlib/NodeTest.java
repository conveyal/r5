package com.conveyal.osmlib;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;


public class NodeTest {

    public static final double EPSILON = 0.000001;

    @Test
    public void testNode(){
		Node node = new Node();
		Assertions.assertEquals(node.getLat(), 0.0, EPSILON);
		Assertions.assertEquals(node.getLon(), 0.0, EPSILON);
		
		node.setLatLon( 47.1, -122.2 );
		Assertions.assertEquals(node.getLat(), 47.1, EPSILON);
		Assertions.assertEquals(node.getLon(), -122.2, EPSILON);
		
		Node node2 = new Node(-45.5, 122.2);
		Assertions.assertEquals(node2.getLat(), -45.5, EPSILON);
		Assertions.assertEquals(node2.getLon(), 122.2, EPSILON);
	}
}
