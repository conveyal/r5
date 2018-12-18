package com.conveyal.r5.profile.entur.rangeraptor.multicriteria.arrivals;

import com.conveyal.r5.profile.entur.api.TestLeg;
import com.conveyal.r5.profile.entur.api.transit.TripScheduleInfo;
import com.conveyal.r5.profile.entur.rangeraptor.transit.TransitCalculator;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TransferStopArrivalTest {

    private static final int A_STOP = 100;
    private static final int TRANSFER_FROM_STOP = 101;
    private static final int ALIGHT_STOP = 102;
    private static final int DEPATURE_TIME = 8 * 60 * 60;
    private static final int LEG_DURATION = 10 * 60;
    private static final int ALIGHT_TIME = DEPATURE_TIME + LEG_DURATION;
    private static final int COST = 500;
    private static final int ROUND = 1;
    private static final int A_TIME = 99;
    private static final TripScheduleInfo A_TRIP = null;

    private static final TransitCalculator TRANSIT_CALCULATOR = new TransitCalculator(A_TIME);
    private static final AccessStopArrival<TripScheduleInfo> ACCESS_ARRIVAL = new AccessStopArrival<>(A_STOP, A_TIME, A_TIME, COST, TRANSIT_CALCULATOR);
    private static final TransitStopArrival<TripScheduleInfo> TRANSIT_ARRIVAL = new TransitStopArrival<>(ACCESS_ARRIVAL, TRANSFER_FROM_STOP, A_TIME, A_TIME, A_TRIP);

    private TransferStopArrival<TripScheduleInfo> subject = new TransferStopArrival<>(TRANSIT_ARRIVAL, new TestLeg(ALIGHT_STOP, LEG_DURATION, COST), ALIGHT_TIME);



    @Test
    public void arrivedByTransfer() {
        assertTrue(subject.arrivedByTransfer());
        assertFalse(subject.arrivedByTransit());
        assertFalse(subject.arrivedByAccessLeg());
    }

    @Test
    public void transferFromStop() {
        assertEquals(TRANSFER_FROM_STOP, subject.transferFromStop());
    }

    @Test
    public void stop() {
        assertEquals(ALIGHT_STOP, subject.stop());
    }

    @Test
    public void arrivalTime() {
        assertEquals(ALIGHT_TIME, subject.arrivalTime());
    }

    @Test
    public void departureTime() {
        assertEquals(DEPATURE_TIME, subject.departureTime());
    }

    @Test
    public void cost() {
        assertEquals(2 * COST, subject.cost());
    }

    @Test
    public void round() {
        assertEquals(ROUND, subject.round());
    }

    @Test
    public void previous() {
        assertEquals(TRANSIT_ARRIVAL, subject.previous());
    }

    @Test
    public void legDuration() {
        assertEquals(LEG_DURATION, subject.legDuration());
    }

    @Test
    public void listStops() {
        assertEquals("[100, 101, 102]", subject.listStops().toString());
    }

    @Test
    public void testToString() {
        assertEquals(
                "TransferStopArrival { Rnd: 1, Stop: 102, Time: 8:10:00 (10:00), Cost: 1000 }",
                subject.toString()
        );
    }

}