package com.conveyal.r5.otp2.rangeraptor.multicriteria.arrivals;

import com.conveyal.r5.otp2.api.TestLeg;
import com.conveyal.r5.otp2.api.transit.TripScheduleInfo;
import com.conveyal.r5.otp2.rangeraptor.transit.TransitCalculator;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class TransferStopArrivalTest {

    private static final int BOARD_SLACK = 80;

    private static final int ACCESS_TO_STOP = 100;
    private static final int ACCESS_DEPARTURE_TIME = 8 * 60 * 60;
    private static final int ACCESS_DURATION = 300;
    private static final int ACCESS_COST = 500;


    private static final int TRANSIT_TO_STOP = 101;
    private static final int TRANSIT_BOARD_TIME = 9 * 60 * 60;
    private static final int TRANSIT_LEG_DURATION = 1200;
    private static final int TRANSIT_ALIGHT_TIME = TRANSIT_BOARD_TIME + TRANSIT_LEG_DURATION;
    private static final int TRANSIT_COST = 200;
    private static final TripScheduleInfo TRANSIT_TRIP = null;
    private static final int ROUND = 1;


    private static final int TRANSFER_TO_STOP = 102;
    private static final int TRANSFER_LEG_DURATION = 360;
    private static final int TRANSFER_ALIGHT_TIME = TRANSIT_ALIGHT_TIME + TRANSFER_LEG_DURATION;
    private static final int TRANSFER_COST = 400;


    private static final TransitCalculator TRANSIT_CALCULATOR = TransitCalculator.testDummyCalculator(BOARD_SLACK, true);
    private static final AccessStopArrival<TripScheduleInfo> ACCESS_ARRIVAL = new AccessStopArrival<>(
            ACCESS_TO_STOP,
            ACCESS_DEPARTURE_TIME,
            ACCESS_DURATION,
            ACCESS_COST,
            TRANSIT_CALCULATOR
    );

    private static final TransitStopArrival<TripScheduleInfo> TRANSIT_ARRIVAL = new TransitStopArrival<>(
            ACCESS_ARRIVAL,
            TRANSIT_TO_STOP,
            TRANSIT_ALIGHT_TIME,
            TRANSIT_BOARD_TIME,
            TRANSIT_TRIP,
            ACCESS_DURATION + BOARD_SLACK + TRANSIT_LEG_DURATION,
            TRANSIT_COST
    );

    private TransferStopArrival<TripScheduleInfo> subject = new TransferStopArrival<>(
            TRANSIT_ARRIVAL,
            new TestLeg(TRANSFER_TO_STOP,TRANSFER_LEG_DURATION),
            TRANSFER_ALIGHT_TIME,
            TRANSFER_COST
    );



    @Test
    public void arrivedByTransfer() {
        assertTrue(subject.arrivedByTransfer());
        assertFalse(subject.arrivedByTransit());
        assertFalse(subject.arrivedByAccessLeg());
    }

    @Test
    public void transferFromStop() {
        assertEquals(TRANSIT_TO_STOP, subject.transferFromStop());
    }

    @Test
    public void stop() {
        assertEquals(TRANSFER_TO_STOP, subject.stop());
    }

    @Test
    public void arrivalTime() {
        assertEquals(TRANSFER_ALIGHT_TIME, subject.arrivalTime());
    }

    @Test
    public void departureTime() {
        assertEquals(TRANSIT_ALIGHT_TIME, subject.departureTime());
    }

    @Test
    public void cost() {
        assertEquals(ACCESS_COST + TRANSIT_COST + TRANSFER_COST, subject.cost());
    }

    @Test
    public void travelDuration() {
        assertEquals(
                ACCESS_DURATION + BOARD_SLACK + TRANSIT_LEG_DURATION + TRANSFER_LEG_DURATION,
                subject.travelDuration()
        );
    }

    @Test
    public void round() {
        assertEquals(ROUND, subject.round());
    }

    @Test
    public void previous() {
        assertSame(TRANSIT_ARRIVAL, subject.previous());
    }

    @Test
    public void testToString() {
        assertEquals(
                "TransferStopArrival { Rnd: 1, Stop: 102, Time: 9:26:00 (9:20:00), Cost: 1100 }",
                subject.toString()
        );
    }

}