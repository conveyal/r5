/**
 * This package contains classes to compare the contents of object graphs in Java.
 * This is intended for testing that a round trip through serialization and deserialization reproduces an identical
 * transportation network representation, and that the processs of building that transportation network is reproducible.
 *
 * This should arguably be a separate Maven / Git project so that it can be reused in multiple other projects.
 * For the time being, we only need to use it in R5 and OpenTripPlanner, and OpenTripPlanner depends on R5, so we keep
 * this code in R5 for simplicity.
 *
 * The object differ started out as a copy of the one supplied by csolem via the Entur OTP branch at
 * https://github.com/entur/OpenTripPlanner/tree/protostuff_poc but has been mostly rewritten at this point.
 */
package com.conveyal.r5.diff;