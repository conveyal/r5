
package com.conveyal.r5.profile;

import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.ShortVector;
import jdk.incubator.vector.VectorSpecies;

/**
 * Experimental helper functions for travel time propagation using the new Vector API in JDK 19.
 * The Vector API is an 'incubating' feature to ensure the compiler emits SIMD instructions for repetitive
 * pipelined operations on large contiguous arrays.
 *
 * Incubating JDK features are subpackages located under jdk.incubator. In order to use them, you must import them but
 * also make them visible through the Java package system. If your project has a module-info.java you can add something
 * like: module r5.main { requires jdk.incubator.vector; }
 *
 * If you don't have a module-info (are using the automatic or unnamed modules) you need to add JVM flags both when
 * compiling and running: --add-modules jdk.incubator.vector
 * In IntelliJ this would be under the Run/Debug configuration, as well as the compiler settings at:
 * Preferences -> Build -> Compiler -> Java Compiler -> Additional command line parameters.
 *
 * This is in contrast to preview JDK features (~95% complete), which must be enabled with the –enable-preview compiler
 * flag, and experimental JDK features (~25% complete) which may be unstable and must each be enabled with their own
 * individual compiler flag.
 *
 * From https://openjdk.org/jeps/338: A scalar loop is required at the end, duplicating code...
 * [this] issue will not be fully addressed by this JEP and will be the subject of future work.
 * As shown in the first example, you can use masks to implement vector computation without tail processing.
 * We anticipate that such masked loops will work well for a range of architectures, including x64 and ARM,
 * but will require additional runtime compiler support to generate maximally efficient code.
 * Such work on masked loops, though important, is beyond the scope of this JEP.
 *
 * Benchmarking:
 * Running outside debugger (debug mode seems to inhibit optimization and yields slower code).
 * Total propagation time Netherlands 7-11AM (240 minutes) after repeated runs to trigger JIT:
 * JVM 11 3.4s with scalar loops only
 * JVM 19 3.4s with scalar loops only
 * JVM 19 2.4s with Vector API (29% speedup)
 * JVM 19 2.0s with Vector API operating on shorts (41% speedup)
 *
 * (Note that it helps to set ExecutionTimer log level to DEBUG in logback.xml to see the breakdown.)
 * Speedups are only about 1/3 rather than 4-8X, so auto-vectorization must already be getting us part of the way there.
 * Memory bus speed may also limit improvements. Using narrower data types (e.g. when transposing travel times) appears
 * to help significantly and should reduce memory consumption on large sets of destinations.
 * A 16 bit signed short is 2**16/2 = 9.1 hours at 1 sec resolution.
 * We could even reserve half the positive range (everything > 4.5 hours) for unreached, essentially using the high bit
 * as a flag for UNREACHED, which would eliminate the need to check for UNREACHED or overflow until final output.
 * This short typing can be carried all the way to TravelTimeResult#values.
 *
 * Parallelization of propagation in geographic blocks could help with memory locality and cache.
 * This is complicated by the fact that the propagator class has state and is not threadsafe.
 *
 * We should also prototype path retention with SIMD blend operations.
 *
 * It would be good to check whether both forms are emitting SIMD instructions. https://stackoverflow.com/a/17142855/778449
 */
public class VectorizedPropagation {

    static final boolean ENABLE_VECTOR_TEST = true;

    /**
     * The preferred size of vector for the particular hardware we're running on.
     * Declared static final so the compiler will treat it as a constant and optimize more aggressively.
     */
    static final VectorSpecies<Short> SPECIES = ShortVector.SPECIES_PREFERRED;

    /** Math.min does not have an overload for short parameters. */
    static short min (short a, short b) {
        return (a <= b) ? a : b;
    }

    /** Math.max does not have an overload for short parameters. */
    static short max (short a, short b) {
        return (a >= b) ? a : b;
    }

    public static final short SHORT_UNREACHED_THRESHOLD = Short.MAX_VALUE / 2;

    /**
     * Clamp 32 bit int to half of 16 bit range (4.5 hours), mapping Integer.MAX_VALUE (FastRaptorWorker.UNREACHED)
     * and any excessive travel times to SHORT_UNREACHED_THRESHOLD.
     */
    public static short timeToShort(int time) {
        if (time >= SHORT_UNREACHED_THRESHOLD) return SHORT_UNREACHED_THRESHOLD;
        return (short) time;
    }

    /** Expand 16 bit int to 32 bits, interpreting values above SHORT_UNREACHED_THRESHOLD as FastRaptorWorker.UNREACHED */
    public static int timeToInt (short time) {
        if (time >= SHORT_UNREACHED_THRESHOLD) return Integer.MAX_VALUE;
        return (int) time;
    }

    /**
     * @param timesToStop the travel times to one particular stop
     * @param stopToDestTime number of seconds to travel from that stop to the destination point
     * @param timesToDestination this is the output array and will be overwritten by a call to this method
     */
    static void propagate (short[] timesToStop, short stopToDestTime, short[] timesToDestination) {
        if (timesToStop == null) return; // Stop was not linked to network
        int i = 0;
        int upperBound = SPECIES.loopBound(timesToStop.length);
        for (; i < upperBound; i += SPECIES.length()) {
            var tts = ShortVector.fromArray(SPECIES, timesToStop, i);
            var ttd = ShortVector.fromArray(SPECIES, timesToDestination, i);
            // .max(tts) should handle overflow, i.e. if adding something makes it less (negative) keep the higher value
            // Alternatively the following is about the same speed:
            // VectorMask stopReached = tts.lt(maxTravelTimeSeconds).not();
            // tts.add(stopToDestTime).blend(Integer.MAX_VALUE, stopReached).min(ttd);
            tts.add(stopToDestTime).min(ttd).intoArray(timesToDestination, i);
        }
        for (; i < timesToStop.length; i++) {
            // In Java, the sum of two shorts is an int. Why? Because the language specification says so.
            timesToDestination[i] = min(timesToDestination[i], (short)(timesToStop[i] + stopToDestTime));
        }
    }
}
