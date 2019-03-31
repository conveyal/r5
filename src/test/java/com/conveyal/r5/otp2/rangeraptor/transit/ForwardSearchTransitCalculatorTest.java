package com.conveyal.r5.otp2.rangeraptor.transit;

import com.conveyal.r5.otp2.api.TestTripSchedule;
import com.conveyal.r5.otp2.api.transit.IntIterator;
import org.junit.Test;

import static com.conveyal.r5.otp2.util.TimeUtils.hm2time;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ForwardSearchTransitCalculatorTest {
    private static final int TRIP_SEARCH_BINARY_SEARCH_THRESHOLD = 7;

    private int boardSlackInSeconds = 30;
    private int earliestDepartureTime = hm2time(8, 0);
    private int searchWindowSizeInSeconds = 2 * 60 * 60;
    private int latestAcceptableArrivalTime = hm2time(16, 0);
    private int iterationStep = 60;


    private TransitCalculator create() {
        return new ForwardSearchTransitCalculator(
                TRIP_SEARCH_BINARY_SEARCH_THRESHOLD,
                boardSlackInSeconds,
                earliestDepartureTime,
                searchWindowSizeInSeconds,
                latestAcceptableArrivalTime,
                iterationStep
        );
    }

    @Test
    public void isBest() {
        TransitCalculator subject = create();

        assertTrue(subject.isBest(10, 11));
        assertFalse(subject.isBest(11, 10));
        assertFalse(subject.isBest(10, 10));
    }

    @Test
    public void exceedsTimeLimit() {
        latestAcceptableArrivalTime = 1200;
        TransitCalculator subject = create();

        assertFalse(subject.exceedsTimeLimit(0));
        assertFalse(subject.exceedsTimeLimit(1200));
        assertTrue(subject.exceedsTimeLimit(1201));

        latestAcceptableArrivalTime = hm2time(16, 0);

        assertEquals(
                "The arrival time exceeds the time limit, arrive to late: 16:00:00.",
                create().exceedsTimeLimitReason()
        );

        latestAcceptableArrivalTime = -1;
        subject = create();
        assertFalse(subject.exceedsTimeLimit(0));
        assertFalse(subject.exceedsTimeLimit(2_000_000_000));
    }

    @Test
    public void oneIterationOnly() {
        TransitCalculator subject = create();

        assertFalse(subject.oneIterationOnly());

        searchWindowSizeInSeconds = 0;
        subject = create();

        assertTrue(subject.oneIterationOnly());
    }

    @Test
    public void boardSlackInSeconds() {
        boardSlackInSeconds = 120;
        TransitCalculator subject = create();
        assertEquals(620, subject.addBoardSlack(500));
        assertEquals(380, subject.removeBoardSlack(500));
    }

    @Test
    public void earliestBoardTime() {
        boardSlackInSeconds = 120;
        TransitCalculator subject = create();
        assertEquals(220, subject.earliestBoardTime(100));
    }

    @Test
    public void duration() {
        assertEquals(600, create().plusDuration(500, 100));
        assertEquals(400, create().minusDuration(500, 100));
        assertEquals(400, create().duration(100, 500));
    }

    @Test
    public void unreachedTime() {
        assertEquals(Integer.MAX_VALUE, create().unreachedTime());
    }

    @Test
    public void originDepartureTime() {
        boardSlackInSeconds = 50;
        assertEquals(650, create().originDepartureTime(1200, 500));
    }

    @Test
    public void latestArrivalTime() {
        TestTripSchedule s = TestTripSchedule.createTripScheduleUseingArrivalTimes(500);
        assertEquals(500, create().latestArrivalTime(s, 0));
    }

    @Test
    public void rangeRaptorMinutes() {
        earliestDepartureTime = 500;
        searchWindowSizeInSeconds = 200;
        iterationStep = 100;

        assertIntIterator(create().rangeRaptorMinutes(), 600, 500);
    }

    @Test
    public void patternStopIterator() {
        assertIntIterator(create().patternStopIterator(2), 0, 1);
        assertIntIterator(create().patternStopIterator(3, 6), 4, 5);
    }


    private void assertIntIterator(IntIterator it, int ... values) {
        for (int v : values) {
            assertTrue(it.hasNext());
            assertEquals(v, it.next());
        }
        assertFalse(it.hasNext());
    }
}