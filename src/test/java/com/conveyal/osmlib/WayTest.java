package com.conveyal.osmlib;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class WayTest {
	@Test
    public void testWay(){
		Way way = new Way();
		Assertions.assertNotNull(way);
	}
}
