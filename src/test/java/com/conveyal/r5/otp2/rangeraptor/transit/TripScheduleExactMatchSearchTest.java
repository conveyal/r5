package com.conveyal.r5.otp2.rangeraptor.transit;

import com.conveyal.r5.otp2.api.TestTripPattern;
import com.conveyal.r5.otp2.api.TestTripSchedule;
import org.junit.Test;

import static com.conveyal.r5.otp2.api.TestTripSchedule.createTripSchedule;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TripScheduleExactMatchSearchTest {

    private static final int STOP = 0;

    // The test dummy calculators have a fixed iteration step of 60 seconds
    private static final int ITERATION_STEP = 60;
    private static final int TRIP_TIME = 500;
    private static final boolean FORWARD = true;
    private static final boolean REVERSE = false;
    private static final TestTripSchedule TRIP_SCHEDULE = createTripSchedule(0, TRIP_TIME);
    private static final TestTripPattern TRIP_PATTERN = new TestTripPattern(TRIP_SCHEDULE);

    private TripScheduleSearch<TestTripSchedule> subject;

    public void setup(boolean forward) {
        TransitCalculator calculator = TransitCalculator.testDummyCalculator(200, forward);
        subject = calculator.createExactTripSearch(TRIP_PATTERN, (t) -> false);
    }

    @Test
    public void testForwardSearch() {
        // Given:
        //   A forward search and a fixed trip departure time (TRIP_TIME = 500)
        //   To test this, we change this earliest departure time .
        setup(FORWARD);
        int earliestDepartureTime;

        earliestDepartureTime = TRIP_TIME;
        assertTrue(subject.search(earliestDepartureTime, STOP));

        earliestDepartureTime = TRIP_TIME - ITERATION_STEP + 1;
        assertTrue(subject.search(earliestDepartureTime, STOP));

        earliestDepartureTime = TRIP_TIME + 1;
        assertFalse(subject.search(earliestDepartureTime, STOP));

        earliestDepartureTime = TRIP_TIME - ITERATION_STEP;
        assertFalse(subject.search(earliestDepartureTime, STOP));

        earliestDepartureTime = TRIP_TIME;
        assertFalse(subject.search(earliestDepartureTime, STOP, 0));
    }

    @Test
    public void testReverseSearch() {
        setup(REVERSE);
        int limit;

        limit = TRIP_TIME;
        assertTrue(subject.search(limit, STOP));

        limit = TRIP_TIME + ITERATION_STEP - 1;
        assertTrue(subject.search(limit, STOP));

        limit = TRIP_TIME - 1;
        assertFalse(subject.search(limit, STOP));

        limit = TRIP_TIME + ITERATION_STEP;
        assertFalse(subject.search(limit, STOP));
    }

    @Test
    public void getCandidateTrip() {
        setup(FORWARD);
        subject.search(TRIP_TIME, STOP);
        assertEquals(TRIP_SCHEDULE, subject.getCandidateTrip());
    }

    @Test
    public void getCandidateTripIndex() {
        setup(FORWARD);
        subject.search(TRIP_TIME, STOP);
        assertEquals(0, subject.getCandidateTripIndex());
    }

    @Test
    public void getCandidateTripTime() {
        setup(FORWARD);
        subject.search(TRIP_TIME, STOP);
        assertEquals(TRIP_TIME, subject.getCandidateTripTime());
    }
}