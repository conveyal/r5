package com.conveyal.r5.analyst.decay;

import com.google.common.primitives.Doubles;
import gnu.trove.list.TDoubleList;
import gnu.trove.list.array.TDoubleArrayList;
import junit.framework.TestCase;
import org.apache.commons.math3.util.FastMath;
import org.junit.Test;

import static com.conveyal.r5.analyst.decay.DecayFunction.FOUR_HOURS_IN_SECONDS;
import static com.conveyal.r5.analyst.decay.DecayFunction.TWO_HOURS_IN_SECONDS;
import static com.conveyal.r5.analyst.decay.DecayFunction.ZERO_EPSILON;
import static com.google.common.base.Preconditions.checkState;

public class TestDecayFunctions extends TestCase {

    private static final int TEN_MINUTES_IN_SECONDS = 10 * 60;

    @Test
    public void testStepFunction () {
        DecayFunction stepFunction = new StepDecayFunction();
        testFunctionCharacteristics(stepFunction);
    }

    @Test
    public void testLogisticDecay () {
        for (int standardDeviationMinutes = 1; standardDeviationMinutes < 20; standardDeviationMinutes++) {
            LogisticDecayFunction function = new LogisticDecayFunction();
            function.standardDeviationMinutes = standardDeviationMinutes;
            testFunctionCharacteristics(function);
        }
    }

    @Test
    public void testLinearDecay () {
        for (int widthMinutes = 0; widthMinutes <= 20; widthMinutes++) {
            LinearDecayFunction function = new LinearDecayFunction();
            function.widthMinutes = widthMinutes;
            testFunctionCharacteristics(function);
        }
    }

    @Test
    public void testExponentialDecay () {
        ExponentialDecayFunction movableFunction = new ExponentialDecayFunction();
        testFunctionCharacteristics(movableFunction);
        // printFunctionValues(movableFunction);
        FixedExponentialDecayFunction fixedFunction = new FixedExponentialDecayFunction();
        // Set constant for a half life of 10 minutes (in seconds)
        fixedFunction.decayConstant = -(FastMath.log(0.5) / TEN_MINUTES_IN_SECONDS);
        testFunctionCharacteristics(fixedFunction);
        for (int t = 0; t < 120; t++) {
            int travelTimeSeconds = t * 60;
            assertEquals(
                    "Fixed function should produce values identical to those for a half-life of 10 minutes.",
                     movableFunction.computeWeight(TEN_MINUTES_IN_SECONDS, travelTimeSeconds),
                     fixedFunction.computeWeight(0, travelTimeSeconds),
                    0.0001
            );
        }
    }

    /**
     * This method can be called from within a test to print out a table of function values, which can be plotted
     * as a visual sanity check.
     */
    public void printFunctionValues(DecayFunction function) {
        for (int cutoffMinutes = 5; cutoffMinutes <= 25; cutoffMinutes += 5) {
            TDoubleList row = new TDoubleArrayList();
            row.add(cutoffMinutes);
            int cutoffSeconds = cutoffMinutes * 60;
            for (int travelTimeMinutes = 0; travelTimeMinutes <= 120; travelTimeMinutes += 1) {
                int travelTimeSeconds = travelTimeMinutes * 60;
                row.add(function.computeWeight(cutoffSeconds, travelTimeSeconds));
            }
            System.out.println(Doubles.join(",", row.toArray()));
        }
    }

    /**
     * Check that:
     * For every cutoff, as travel times increase, weights decrease.
     * All weights returned are in the range [0...1].
     * The function properly identifies its zero point.
     */
    private static void testFunctionCharacteristics (DecayFunction function) {
        function.prepare();
        for (int cutoffMinutes = 0; cutoffMinutes < 120; cutoffMinutes++) {
            int cutoffSeconds = cutoffMinutes * 60;
            // First, check that the function is monotonically decreasing and all outputs are in range.
            double prevWeight = Double.POSITIVE_INFINITY;
            for (int s = 0; s <= TWO_HOURS_IN_SECONDS; s += 1) {
                double weight = function.computeWeight(cutoffSeconds, s);
                checkState(weight >= 0, "Weight output must be non-negative.");
                checkState(weight <= 1, "Weight output must not exceed one.");
                checkState(weight <= prevWeight, "Weight function must be monotonically decreasing.");
                prevWeight = weight;
            }
            // Next, check that the function correctly identifies the point where it approaches zero.
            int zero = function.reachesZeroAt(cutoffSeconds);
            checkState(zero >= 0, "Decay function zero point must be zero or positive, but was {}.", zero);
            checkState(zero < FOUR_HOURS_IN_SECONDS, "Decay function zero point must be less than four hours.");
            // Disabling this assertion until we have a policy for functions like exponential, not affected by cutoff
            // checkState(zero >= cutoffSeconds, "Zero point should be at or above cutoff.");
            double zeroValue = Math.abs(function.computeWeight(cutoffSeconds, zero));
            checkState(zeroValue < ZERO_EPSILON, "Decay function output for zero point must be close to zero.");
            double almostZeroValue = Math.abs(function.computeWeight(cutoffSeconds, zero - 1));
            // If we check that they exceed epsilon, then functions that return a precise zero point can fail the test.
            checkState(almostZeroValue > 0, "Values left of zero point must be above zero.");
        }
    }

}
