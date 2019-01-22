package com.conveyal.r5.profile.entur.rangeraptor.multicriteria.arrivals;

import com.conveyal.r5.profile.entur.api.transit.TripScheduleInfo;
import com.conveyal.r5.profile.entur.rangeraptor.transit.TransitCalculator;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AccessStopArrivalTest {

    private static final int BOARD_SLACK = 45;
    private static final int ALIGHT_STOP = 100;
    private static final int DEPATURE_TIME = 8 * 60 * 60;
    private static final int LEG_DURATION = 10 * 60;
    private static final int ALIGHT_TIME = DEPATURE_TIME + LEG_DURATION;
    private static final int BOARD_TIME = DEPATURE_TIME + 30 * 60;
    private static final int COST = 500;

    private static final TransitCalculator TRANSIT_CALCULATOR = TransitCalculator.testDummy(BOARD_SLACK);
    private AccessStopArrival<TripScheduleInfo> subject = new AccessStopArrival<>(ALIGHT_STOP, DEPATURE_TIME, LEG_DURATION, COST, TRANSIT_CALCULATOR);



    @Test
    public void arrivedByAccessLeg() {
        assertTrue(subject.arrivedByAccessLeg());
        assertFalse(subject.arrivedByTransit());
        assertFalse(subject.arrivedByTransfer());
    }

    @Test
    public void stop() {
        assertEquals(ALIGHT_STOP, subject.stop());
    }

    @Test
    public void arrivalTime() {
        assertEquals(ALIGHT_TIME, subject.arrivalTime());
        assertEquals(BOARD_TIME - BOARD_SLACK, subject.arrivalTimeAccess(BOARD_TIME));
    }

    @Test
    public void departureTime() {
        assertEquals(DEPATURE_TIME, subject.departureTime());
        assertEquals(BOARD_TIME - BOARD_SLACK - LEG_DURATION, subject.departureTimeAccess(BOARD_TIME));
    }

    @Test
    public void cost() {
        assertEquals(COST, subject.cost());
    }

    @Test
    public void round() {
        assertEquals(0, subject.round());
    }

    @Test
    public void legDuration() {
        assertEquals(LEG_DURATION, subject.legDuration());
    }

    @Test
    public void listStops() {
        assertEquals("[100]", subject.listStops().toString());
    }

    @Test
    public void testToString() {
        assertEquals(
                "AccessStopArrival { Rnd: 0, Stop: 100, Time: 8:10:00 (10:00), Cost: 500 }",
                subject.toString()
        );
    }

}