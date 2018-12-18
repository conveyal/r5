package com.conveyal.r5.profile.entur.rangeraptor.multicriteria.arrivals;

import com.conveyal.r5.profile.entur.api.transit.TripScheduleInfo;
import com.conveyal.r5.profile.entur.rangeraptor.transit.TransitCalculator;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TransitStopArrivalTest {

    private static final int BOARD_STOP = 100;
    private static final int ALIGHT_STOP = 101;
    private static final int BOARD_TIME = 8 * 60 * 60;
    private static final int LEG_DURATION = 10 * 60;
    private static final int ALIGHT_TIME = BOARD_TIME + LEG_DURATION;
    private static final int COST = 500;
    private static final int ROUND = 1;
    private static final int A_TIME = 99;
    private static final TripScheduleInfo A_TRIP = new TripScheduleInfo() {
        @Override public int arrival(int stopPosInPattern) { return 0; }
        @Override public int departure(int stopPosInPattern) { return 0; }
        @Override public String debugInfo() { return null; }
    };

    private static final TransitCalculator TRANSIT_CALCULATOR = new TransitCalculator(A_TIME);
    private static final AccessStopArrival<TripScheduleInfo> ACCESS_ARRIVAL = new AccessStopArrival<>(BOARD_STOP, A_TIME, A_TIME, COST, TRANSIT_CALCULATOR);

    private TransitStopArrival<TripScheduleInfo> subject = new TransitStopArrival<>(ACCESS_ARRIVAL, ALIGHT_STOP, ALIGHT_TIME,  BOARD_TIME, A_TRIP);


    @Test
    public void round() {
        assertEquals(ROUND, subject.round());
    }

    @Test
    public void stop() {
        assertEquals(ALIGHT_STOP, subject.stop());
    }

    @Test
    public void arrivedByTransit() {
        assertTrue(subject.arrivedByTransit());
        assertFalse(subject.arrivedByTransfer());
        assertFalse(subject.arrivedByAccessLeg());
    }

    @Test
    public void boardStop() {
        assertEquals(BOARD_STOP, subject.boardStop());
    }

    @Test
    public void arrivalTime() {
        assertEquals(ALIGHT_TIME, subject.arrivalTime());
    }

    @Test
    public void departureTime() {
        assertEquals(BOARD_TIME, subject.departureTime());
    }

    @Test
    public void legDuration() {
        assertEquals(LEG_DURATION, subject.legDuration());
    }

    @Test
    public void cost() {
        assertEquals(COST, subject.cost());
    }

    @Test
    public void trip() {
        assertEquals(A_TRIP, subject.trip());
    }


    @Test
    public void previous() {
        assertEquals(ACCESS_ARRIVAL, subject.previous());
    }

    @Test
    public void listStops() {
        assertEquals("[100, 101]", subject.listStops().toString());
    }

    @Test
    public void testToString() {
        assertEquals(
                "TransitStopArrival { Rnd: 1, Stop: 101, Time: 8:10:00 (10:00), Cost: 500 }",
                subject.toString()
        );
    }
}