package com.conveyal.analysis.controllers;

import com.conveyal.analysis.components.broker.Broker;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.UUID;

/**
 * Tests that check how parameters received over the HTTP API are validated.
 * These validators should be fairly strict about what they accept, and should not tolerate the presence of things
 * like semicolons or double dashes that indicate attempts to corrupt or gain access to database contents.
 *
 * Arguably we should have another layer of input sanitization that not only refuses but logs anything that contains
 * characters or substrings that could be associated with an attempted attack, and that same validator should be
 * applied to every input (perhaps called from every other input validator).
 */
public class ArgumentValidationTest {

    @Test
    void testIdValidation () {
        Assertions.assertThrows(IllegalArgumentException.class, () -> {
            BrokerController.checkIdParameter("hello", "param");
        });
        Assertions.assertThrows(IllegalArgumentException.class, () -> {
            BrokerController.checkIdParameter("Robert'); DROP TABLE Students;--", "param");
            // https://xkcd.com/327/
        });
        Assertions.assertThrows(IllegalArgumentException.class, () -> {
            BrokerController.checkIdParameter("0123456789012345", "param");
        });
        Assertions.assertThrows(IllegalArgumentException.class, () -> {
            BrokerController.checkIdParameter("0123456789ABCDEF67890ZZZZ5678901", "param");
        });
        Assertions.assertDoesNotThrow(() -> {
            BrokerController.checkIdParameter("0123456789abcDEF6789012345678901", "param");
        });
        Assertions.assertDoesNotThrow(() -> {
            String validUuid = UUID.randomUUID().toString();
            BrokerController.checkIdParameter(validUuid, "param");
        });
    }


}
