package com.conveyal.r5.streets;

import com.conveyal.r5.point_to_point.builder.TNBuilderConfig;
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

    @Before
    public void setUp () {
        // generate a street layer that looks like this
        //     0
        //     |
        //     |/--3
        // 6 --*-- 2
        //     |
        //     4
        streetLayer = new StreetLayer(new TNBuilderConfig());
        VCENTER = streetLayer.vertexStore.addVertex(-122.123, 37.363);
        VN = streetLayer.vertexStore.addVertex(-122.123, 37.364);
        VS = streetLayer.vertexStore.addVertex(-122.123, 37.362);
        VE = streetLayer.vertexStore.addVertex(-122.122, 37.363);
        VNE = streetLayer.vertexStore.addVertex(-122.122, 37.3631);
        VW = streetLayer.vertexStore.addVertex(-122.124, 37.363);

        EN = streetLayer.edgeStore.addStreetPair(VCENTER, VN, 15000, 4).getEdgeIndex();
        EE = streetLayer.edgeStore.addStreetPair(VCENTER, VE, 15000, 2).getEdgeIndex();
        ES = streetLayer.edgeStore.addStreetPair(VCENTER, VS, 15000, 3).getEdgeIndex();
        EW = streetLayer.edgeStore.addStreetPair(VCENTER, VW, 15000, 1).getEdgeIndex();
        ENE = streetLayer.edgeStore.addStreetPair(VCENTER, VNE, 15000, 5).getEdgeIndex();
    }

    @Test
    public void testAngle() throws Exception {
        TurnCostCalculator calculator = new TurnCostCalculator(streetLayer, true);
        assertEquals(1.5 * Math.PI, calculator.computeAngle(EE + 1, ES), 1e-6);
        assertEquals(Math.PI, calculator.computeAngle(EE, EE + 1), 1e-6);
        assertEquals(0, calculator.computeAngle(EW + 1, EE), 1e-6);
        double angle = calculator.computeAngle(EW + 1, ENE);
        assertTrue(angle > 1.75 * Math.PI);
        assertEquals(0.5 * Math.PI, calculator.computeAngle(EE + 1, EN), 1e-6);
    }
}