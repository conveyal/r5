package com.conveyal.osmlib;

import junit.framework.TestCase;

public class NodeTest extends TestCase {
	public void testNode(){
		Node node = new Node();
		assertEquals( node.getLat(), 0.0 );
		assertEquals( node.getLon(), 0.0 );
		
		node.setLatLon( 47.1, -122.2 );
		assertEquals( node.getLat(), 47.1 );
		assertEquals( node.getLon(), -122.2 );
		
		Node node2 = new Node(-45.5, 122.2);
		assertEquals( node2.getLat(), -45.5 );
		assertEquals( node2.getLon(), 122.2 );
	}
}
