package com.conveyal.osmlib;

import org.junit.Test;

import static org.junit.Assert.assertNotNull;

public class WayTest {
	@Test
    public void testWay(){
		Way way = new Way();
		assertNotNull(way);
	}
}
