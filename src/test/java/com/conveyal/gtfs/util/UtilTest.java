package com.conveyal.gtfs.util;

import org.junit.jupiter.api.Test;

import static com.conveyal.gtfs.util.Util.human;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A test suite to verify the functionality of methods in the Util class.
 */
public class UtilTest {

    /**
     * Assert that the human function returns strings that are properly formatted.
     */
    @Test
    public void canHumanize() {
        assertEquals(human(123), "123");
        assertEquals(human(1234), "1k");
        assertEquals(human(1234567), "1.2M");
        assertEquals(human(1234567890), "1.2G");
    }
}
