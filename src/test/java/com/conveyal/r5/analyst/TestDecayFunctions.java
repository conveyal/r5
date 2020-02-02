package com.conveyal.r5.analyst;

import com.google.common.primitives.Doubles;
import gnu.trove.list.TDoubleList;
import gnu.trove.list.array.TDoubleArrayList;
import org.junit.Test;

public class TestDecayFunctions {

    @Test
    public void testLogisticDecay () {
        LogisticDecayFunction function = new LogisticDecayFunction();
        function.standardDeviationSeconds = 60 * 10;
        function.prepare();
        for (int cutoffMinutes = 10; cutoffMinutes < 120; cutoffMinutes += 10) {
            TDoubleList row = new TDoubleArrayList();
            row.add(cutoffMinutes);
            int cutoffSeconds = cutoffMinutes * 60;
            for (int travelTimeMinutes = 0; travelTimeMinutes < 120; travelTimeMinutes += 1) {
                int travelTimeSeconds = travelTimeMinutes * 60;
                row.add(function.computeWeight(cutoffSeconds, travelTimeSeconds));
            }
            System.out.println(Doubles.join(",", row.toArray()));
        }
    }

    private static void testFunctionCharacteristics (DecayFunction function) {
        // move code from com.conveyal.r5.analyst.DecayFunction.checkInvariants
    }

}
