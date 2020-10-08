package com.conveyal.r5.streets;

import com.conveyal.r5.profile.StreetMode;
import org.junit.Test;
import org.locationtech.jts.algorithm.Angle;
import org.locationtech.jts.geom.Coordinate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Created by matthewc on 2/23/16.
 */
public class BasicTraversalTimeCalculatorTest extends TurnTest {
    @Test
    public void testAngle() throws Exception {
        setUp(false);
        BasicTraversalTimeCalculator calculator = new BasicTraversalTimeCalculator(streetLayer, true);
        assertEquals(0.5 * Math.PI, calculator.computeAngle(ee + 1, es), 1e-6);
        assertEquals(Math.PI, calculator.computeAngle(ee, ee + 1), 1e-6);
        assertEquals(0, calculator.computeAngle(ew + 1, ee), 1e-6);
        double angle = calculator.computeAngle(ew + 1, ene);
        assertTrue(angle < 0.15 * Math.PI);
        assertEquals(1.5 * Math.PI, calculator.computeAngle(ee + 1, en), 1e-6);
        assertEquals(1.5 * Math.PI, calculator.computeAngle(es + 1, ee), 1e-6);
    }

    /** Make sure angles are right in the southern hemisphere as well. We scale by the cosine of latitude, which is negative in the southern hemisphere. */
    @Test
    public void testAngleSouthernHemisphere() throws Exception {
        setUp(true);
        BasicTraversalTimeCalculator calculator = new BasicTraversalTimeCalculator(streetLayer, true);
        assertEquals(0.5 * Math.PI, calculator.computeAngle(ee + 1, es), 1e-6);
        assertEquals(Math.PI, calculator.computeAngle(ee, ee + 1), 1e-6);
        assertEquals(0, calculator.computeAngle(ew + 1, ee), 1e-6);
        double angle = calculator.computeAngle(ew + 1, ene);
        assertTrue(angle < 0.15 * Math.PI);
        assertEquals(1.5 * Math.PI, calculator.computeAngle(ee + 1, en), 1e-6);
        assertEquals(1.5 * Math.PI, calculator.computeAngle(es + 1, ee), 1e-6);
    }

    @Test
    public void testCost () throws Exception {
        setUp(false);
        BasicTraversalTimeCalculator calculator = new BasicTraversalTimeCalculator(streetLayer, true);
        assertEquals(calculator.LEFT_TURN, calculator.turnTimeSeconds(ee + 1, es, StreetMode.CAR));
    }

    /**
     * Test that JTS returns angles that are counterclockwise from the positive X axis (so negative angle is south of X
     * axis).
     *
     * This is a completely nonstandard implementation of angles so I wrote a test to ensure it's stable between JTS releases.
     */
    @Test
    public void testJtsAngle () {
        double a0 = Angle.angle(new Coordinate(10, 10), new Coordinate(10, 9));
        double a1 = Angle.angle(new Coordinate(10, 10), new Coordinate(9, 9));
        assertTrue(a1 < a0);
    }
}