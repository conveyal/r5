package com.conveyal.r5.profile.entur.rangeraptor.transit;

import com.conveyal.r5.profile.entur.api.TestTripPattern;
import com.conveyal.r5.profile.entur.api.TestTripSchedule;
import com.conveyal.r5.profile.entur.api.transit.TripPatternInfo;
import com.conveyal.r5.profile.entur.api.transit.TripScheduleInfo;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static com.conveyal.r5.profile.entur.api.TestTripSchedule.tripScheduleByDepartures;

public class TripScheduleBoardSearchTest {
    private static final int TRIPS_THRESHOLD = 7;

    private static final int TIME_A0 = 1000;
    private static final int TIME_A1 = 1500;

    private static final int TIME_B0 = 2000;
    private static final int TIME_B1 = 2500;

    private static final int TIME_S0 = 1900;
    private static final int TIME_S1 = 2600; // Departs after B, but index is before (not in service)

    private static final int TIME_T0 = 2100;
    private static final int TIME_T1 = 2400; // Departs before B, but index is after (not in service)

    private static final int POS_0 = 0;
    private static final int POS_1 = 1;

    private static final int TRIP_A_INDEX = 0;
    private static final int TRIP_B_INDEX = 2;

    // Trips in service
    private TestTripSchedule tripA = tripScheduleByDepartures(TIME_A0, TIME_A1);
    private TestTripSchedule tripB = tripScheduleByDepartures(TIME_B0, TIME_B1);

    // Trips not in service
    private TestTripSchedule tripS = tripScheduleByDepartures(TIME_S0, TIME_S1);
    private TestTripSchedule tripT = tripScheduleByDepartures(TIME_T0, TIME_T1);

    // Pattern with 2 trips (A, B) in service and 2 trips not in service (S, T)
    // The board times for the first stop is ordered, while the last stop is not.
    private TripPatternInfo<TestTripSchedule> pattern = new TestTripPattern(tripA, tripS, tripB, tripT);

    private TripScheduleBoardSearch<TestTripSchedule> subject = new TripScheduleBoardSearch<>(TRIPS_THRESHOLD, pattern, this::skip);

    @Test
    public void noTripFoundAfterLastTripHasLeftThePlatform() {
        assertNoTrip( TIME_B0, POS_0);
        assertNoTrip( TIME_B1, POS_1);
    }

    @Test
    public void boardFirstTrip() {
        assertTrip(TRIP_A_INDEX, TIME_A0, 0, POS_0);
        assertTrip(TRIP_A_INDEX, TIME_A1, 0, POS_1);
    }

    @Test
    public void boardFirstAvailableTripAfterAGivenTime() {
        assertTrip(TRIP_A_INDEX, TIME_A0, TIME_A0-1, POS_0);
        assertTrip(TRIP_A_INDEX, TIME_A1, TIME_A1-1, POS_1);
    }

    @Test
    public void boardFirstAvailableTripButNotSkippedTrips() {
        assertTrip(TRIP_B_INDEX, TIME_B0, TIME_A0, POS_0);
        assertTrip(TRIP_B_INDEX, TIME_B1, TIME_A1, POS_1);
    }

    @Test
    public void noTripsToBoardInEmptyPattern() {
        pattern = new TestTripPattern();
        subject = new TripScheduleBoardSearch<>(TRIPS_THRESHOLD, pattern, this::skip);
        assertNoTrip(0, 0);
    }

    @Test
    public void findTripWithGivenTripIndexUpperBound() {
        // Given the default pattern with the following trips: tripA, tripB
        pattern = new TestTripPattern(tripA, tripB);
        subject = new TripScheduleBoardSearch<>(TRIPS_THRESHOLD, pattern, this::skip);

        // Then we expect to find trip A when `tripIndexUpperBound` is B´s index
        assertTrip(0, TIME_A0, 0, POS_0, 1);

        // An then no trip if `tripIndexUpperBound` equals first trip index
        assertNoTrip(0, POS_0, 0);
    }

    @Test
    public void findTripWithGivenTripIndexUpperBoundButNotSkipedTrips() {
        // Given the default pattern with the following trips: tripA, tripS, tripB, tripT

        // Then we expect to find trip B when `tripIndexUpperBound` is large enough
        assertTrip(TRIP_B_INDEX, TIME_B0, TIME_A0, POS_0, TRIP_B_INDEX + 1);
        assertTrip(TRIP_B_INDEX, TIME_B1, TIME_A1, POS_1, TRIP_B_INDEX + 1);

        // But NOT when `tripIndexUpperBound` equals trip B´s index
        assertNoTrip(TIME_A0, POS_0, TRIP_B_INDEX);
        assertNoTrip(TIME_A1, POS_1, TRIP_B_INDEX);
    }

    @Test
    public void boardFirstAvailableTripForABigNumberOfTrips() {
        // For a pattern with N trip schedules, where the first trip departure is at time 100
        int n = TRIPS_THRESHOLD;
        int N = 3 * n + 7;

        List<TestTripSchedule> tripSchedules = new ArrayList<>();
        int departureTime = 0;

        for (int i = 0; i < N; i++) {
            departureTime += 100;
            tripSchedules.add(tripScheduleByDepartures(departureTime));
        }
        pattern = new TestTripPattern(tripSchedules);
        subject = new TripScheduleBoardSearch<>(TRIPS_THRESHOLD, pattern, this::skip);


        // Test some random boardings
        assertNoTrip(100 * N, 0);

        for (int i = 0; i < N; i += (n/2 - 1)) {
            int expBoardTime = 100 * (i + 1);
            int earliestBoardTime = expBoardTime - 1;

            assertTrip(i, expBoardTime, earliestBoardTime, 0);
            assertNoTrip(earliestBoardTime, 0, i);
        }
    }

    @Test
    public void assertTripIsFoundEvenIfItIsBeforeTheBinarySearchUpperAndLowerBound() {
        // Given a pattern with n+1 trip schedules
        List<TestTripSchedule> tripSchedules = new ArrayList<>();

        // Where the first trip is in service
        tripSchedules.add(tripB);

        // And where the N following trips are NOT in service, but with acceptable boarding times
        addNTimes(tripSchedules, tripT, TRIPS_THRESHOLD);

        pattern = new TestTripPattern(tripSchedules);
        subject = new TripScheduleBoardSearch<>(TRIPS_THRESHOLD, pattern, this::skip);

        // Then we expect to find trip B, even if it is before the binary search window
        assertTrip(0, TIME_B0, TIME_B0-1, POS_0);
        assertTrip(0, TIME_B1, TIME_B1-1, POS_1);
    }

    @Test
    public void assertTripIsFoundEvenIfItIsAfterTheBinarySearchUpperAndLowerBound() {
        // Given a pattern with n+1 trip schedules
        List<TestTripSchedule> tripSchedules = new ArrayList<>();

        // Where the N first trips are NOT in service, but with acceptable boarding times
        addNTimes(tripSchedules, tripS, TRIPS_THRESHOLD);

        // And where the following trip is in service
        tripSchedules.add(tripB);

        pattern = new TestTripPattern(tripSchedules);
        subject = new TripScheduleBoardSearch<>(TRIPS_THRESHOLD, pattern, this::skip);

        // Then we expect to find trip B, even if it is after the binary search window
        assertTrip(TRIPS_THRESHOLD, TIME_B1, TIME_B1-1, POS_1);
        assertNoTrip(TIME_B1, POS_1);
    }

    private <T extends TripScheduleInfo> boolean skip(T trip) {
        return trip == tripS || trip == tripT;
    }

    private void assertTrip(int expectedTripIndex, int expectedBoardTime, int earliestBoardTime, int stopPosition) {
        Assert.assertTrue("Trip found", subject.search(earliestBoardTime, stopPosition));
        Assert.assertEquals("Trip index", expectedTripIndex, subject.candidateTripIndex);
        Assert.assertEquals("Board time", expectedBoardTime, subject.candidateTrip.departure(stopPosition));
    }

    private void assertTrip(int expectedTripIndex, int expectedBoardTime, int earliestBoardTime, int stopPosition, int tripIndexUpperBound) {
        Assert.assertTrue("Trip found", subject.search(tripIndexUpperBound, earliestBoardTime, stopPosition));
        Assert.assertEquals("Trip index", expectedTripIndex, subject.candidateTripIndex);
        Assert.assertEquals("Board time", expectedBoardTime, subject.candidateTrip.departure(stopPosition));
    }

    private void assertNoTrip(int earliestBoardTime, int stopPosition) {
        Assert.assertFalse(subject.search(earliestBoardTime, stopPosition));
    }

    private void assertNoTrip(int earliestBoardTime, int stopPosition, int tripIndexUpperBound) {
        Assert.assertFalse(subject.search(tripIndexUpperBound, earliestBoardTime, stopPosition));
    }
    private static void addNTimes(List<TestTripSchedule> trips, TestTripSchedule tripS, int n) {
        for (int i = 0; i < n; i++) {
            trips.add(tripS);
        }
    }
}
