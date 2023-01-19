package com.conveyal.analysis.controllers;

import com.conveyal.analysis.components.broker.Broker;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.UUID;

/**
 *
 */
public class ArgumentValidationTest {

    @Test
    void testIdValidation () {
        Assertions.assertThrows(IllegalArgumentException.class, () -> {
            BrokerController.checkIdParameter("hello", "param");
        });
        Assertions.assertThrows(IllegalArgumentException.class, () -> {
            BrokerController.checkIdParameter("Robert'; DROP TABLE Students;--", "param");
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
