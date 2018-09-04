package com.conveyal.r5.speed_test.test;

/**
 * The purpose of this exception is to signal test assertion errors.
 */
class TestAssertException extends RuntimeException {
    TestAssertException() {
        super("Test assert errors");
    }
}
