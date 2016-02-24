package com.conveyal.r5.streets;

import com.conveyal.r5.point_to_point.builder.TNBuilderConfig;
import com.conveyal.r5.profile.Mode;
import com.vividsolutions.jts.algorithm.Angle;
import com.vividsolutions.jts.geom.Coordinate;
import junit.framework.TestCase;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;
import static org.junit.Assert.assertEquals;

/**
 * Created by matthewc on 2/23/16.
 */
public class TurnCostCalculatorTest extends TestCase {

    public StreetLayer streetLayer;

    // center vertex index, n/s/e/w vertex indices, n/s/e/w edge indices (always starting from center).
    public int VCENTER, VN, VS, VE, VW, VNE, EN, ES, EE, EW, ENE;

    public void setUp (boolean southernHemisphere) {
        // generate a street layer that looks like this
        //     0
        //     |
        //     |/--3
        // 6 --*-- 2
        //     |
        //     4

        double latOffset = southernHemisphere ? -60 : 0;

        streetLayer = new StreetLayer(new TNBuilderConfig());
        VCENTER = streetLayer.vertexStore.addVertex(37.363 + latOffset, -122.123);
        VN = streetLayer.vertexStore.addVertex(37.364 + latOffset, -122.123);
        VS = streetLayer.vertexStore.addVertex(37.362 + latOffset, -122.123);
        VE = streetLayer.vertexStore.addVertex(37.363 + latOffset, -122.122);
        VNE = streetLayer.vertexStore.addVertex(37.3631 + latOffset, -122.122);
        VW = streetLayer.vertexStore.addVertex(37.363 + latOffset, -122.124);

        EN = streetLayer.edgeStore.addStreetPair(VCENTER, VN, 15000, 4).getEdgeIndex();
        EE = streetLayer.edgeStore.addStreetPair(VCENTER, VE, 15000, 2).getEdgeIndex();
        ES = streetLayer.edgeStore.addStreetPair(VCENTER, VS, 15000, 3).getEdgeIndex();
        EW = streetLayer.edgeStore.addStreetPair(VCENTER, VW, 15000, 1).getEdgeIndex();
        ENE = streetLayer.edgeStore.addStreetPair(VCENTER, VNE, 15000, 5).getEdgeIndex();
    }

    @Test
    public void testAngle() throws Exception {
        setUp(false);
        TurnCostCalculator calculator = new TurnCostCalculator(streetLayer, true);
        assertEquals(0.5 * Math.PI, calculator.computeAngle(EE + 1, ES), 1e-6);
        assertEquals(Math.PI, calculator.computeAngle(EE, EE + 1), 1e-6);
        assertEquals(0, calculator.computeAngle(EW + 1, EE), 1e-6);
        double angle = calculator.computeAngle(EW + 1, ENE);
        assertTrue(angle < 0.15 * Math.PI);
        assertEquals(1.5 * Math.PI, calculator.computeAngle(EE + 1, EN), 1e-6);
        assertEquals(1.5 * Math.PI, calculator.computeAngle(ES + 1, EE), 1e-6);
    }

    /** Make sure angles are right in the southern hemisphere as well. We scale by the cosine of latitude, which is negative in the southern hemisphere. */
    @Test
    public void testAngleSouthernHemisphere() throws Exception {
        setUp(true);
        TurnCostCalculator calculator = new TurnCostCalculator(streetLayer, true);
        assertEquals(0.5 * Math.PI, calculator.computeAngle(EE + 1, ES), 1e-6);
        assertEquals(Math.PI, calculator.computeAngle(EE, EE + 1), 1e-6);
        assertEquals(0, calculator.computeAngle(EW + 1, EE), 1e-6);
        double angle = calculator.computeAngle(EW + 1, ENE);
        assertTrue(angle < 0.15 * Math.PI);
        assertEquals(1.5 * Math.PI, calculator.computeAngle(EE + 1, EN), 1e-6);
        assertEquals(1.5 * Math.PI, calculator.computeAngle(ES + 1, EE), 1e-6);
    }

    @Test
    public void testCost () throws Exception {
        setUp(false);
        TurnCostCalculator calculator = new TurnCostCalculator(streetLayer, true);
        assertEquals(calculator.LEFT_TURN, calculator.computeTurnCost(EE + 1, ES, Mode.CAR));
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