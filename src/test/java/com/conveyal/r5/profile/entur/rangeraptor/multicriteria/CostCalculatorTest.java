package com.conveyal.r5.profile.entur.rangeraptor.multicriteria;

import com.conveyal.r5.profile.entur.api.TestTripSchedule;
import com.conveyal.r5.profile.entur.api.request.MultiCriteriaCostFactors;
import com.conveyal.r5.profile.entur.api.request.RequestBuilder;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class CostCalculatorTest {

    private static final int BOARD_COST = 5;
    private static final double WALK_RELUCTANCE_FACTOR = 2.0;
    private static final double WAIT_RELUCTANCE_FACTOR = 0.5;

    private static final int T1 = 1;
    private static final int T2 = 2;
    private static final int T3 = 3;
    private static final int T4 = 4;

    private CostCalculator subject = new CostCalculator(MultiCriteriaCostFactors.DEFAULTS);

    @Before
    public void setup() {
        RequestBuilder<TestTripSchedule> builder = new RequestBuilder<>();

        builder.multiCriteriaBoardCost(BOARD_COST);
        builder.multiCriteriaWalkReluctanceFactor(WALK_RELUCTANCE_FACTOR);
        builder.multiCriteriaWaitReluctanceFactor(WAIT_RELUCTANCE_FACTOR);

        subject = new CostCalculator(builder.buildMcCostFactors());
    }

    @Test
    public void transitArrivalCost() {
        assertEquals("Cost board cost", 500, subject.transitArrivalCost(T1, T1, T1));
        assertEquals("Cost transit + board cost", 600, subject.transitArrivalCost(T1, T1, T2));
        assertEquals("Cost wait + board", 550, subject.transitArrivalCost(T1, T2, T2));
        assertEquals("wait + board + transit", 750, subject.transitArrivalCost(T1, T2, T4));
    }

    @Test
    public void walkCost() {
        assertEquals(200, subject.walkCost(T1));
        assertEquals(600, subject.walkCost(T3));
    }
}