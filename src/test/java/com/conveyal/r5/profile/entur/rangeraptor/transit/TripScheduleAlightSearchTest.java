package com.conveyal.r5.profile.entur.rangeraptor.transit;

import com.conveyal.r5.profile.entur.api.TestTripPattern;
import com.conveyal.r5.profile.entur.api.TestTripSchedule;
import com.conveyal.r5.profile.entur.api.transit.TripPatternInfo;
import com.conveyal.r5.profile.entur.api.transit.TripScheduleInfo;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class TripScheduleAlightSearchTest {
    private static final int TRIPS_THRESHOLD = 9;

    private static final int TIME_A0 = 1000;
    private static final int TIME_A1 = 1500;

    private static final int TIME_B0 = 2000;
    private static final int TIME_B1 = 2500;

    private static final int TIME_S0 =  900;
    private static final int TIME_S1 = 1600; // Arrives after A, but index is before (not in service)

    private static final int TIME_T0 = 1100;
    private static final int TIME_T1 = 1400; // Arrives before B, but index is after (not in service)

    private static final int TIME_LATE = 5000;

    private static final int POS_0 = 0;
    private static final int POS_1 = 1;

    private static final int TRIP_A_INDEX = 1;
    private static final int TRIP_B_INDEX = 3;

    // Trips in service
    private TestTripSchedule tripA = new TestTripSchedule(TIME_A0, TIME_A1);
    private TestTripSchedule tripB = new TestTripSchedule(TIME_B0, TIME_B1);

    // Trips not in service
    private TestTripSchedule tripS = new TestTripSchedule(TIME_S0, TIME_S1);
    private TestTripSchedule tripT = new TestTripSchedule(TIME_T0, TIME_T1);

    // Pattern with 2 trips (A, B) in service and 2 trips not in service (S, T)
    // The board times for the first stop is ordered, while the last stop is not.
    private TripPatternInfo<TestTripSchedule> pattern = new TestTripPattern(tripS, tripA, tripT, tripB);

    private TripScheduleAlightSearch<TestTripSchedule> subject = new TripScheduleAlightSearch<>(TRIPS_THRESHOLD, pattern, this::skip);

    @Test
    public void noTripFoundBeforeFirstTripHasArrived() {
        assertNoTrip( TIME_A0, POS_0);
        assertNoTrip( TIME_A1, POS_1);
    }

    @Test
    public void boardLatestTrip() {
        assertTrip(TRIP_B_INDEX, TIME_B0, TIME_LATE, POS_0);
        assertTrip(TRIP_B_INDEX, TIME_B1, TIME_LATE, POS_1);
    }

    @Test
    public void boardLatestAvailableTripBeforeGivenArrivalTime() {
        assertTrip(TRIP_A_INDEX, TIME_A0, TIME_A0+1, POS_0);
        assertTrip(TRIP_A_INDEX, TIME_A1, TIME_A1+1, POS_1);
    }

    @Test
    public void boardFirstAvailableTripButNotSkippedTrips() {
        assertTrip(TRIP_A_INDEX, TIME_A0, TIME_B0, POS_0);
        assertTrip(TRIP_A_INDEX, TIME_A1, TIME_B1, POS_1);
    }

    @Test
    public void noTripsToBoardInEmptyPattern() {
        pattern = new TestTripPattern();
        subject = new TripScheduleAlightSearch<>(TRIPS_THRESHOLD, pattern, this::skip);
        assertNoTrip(0, 0);
    }

    @Test
    public void findTripWithGivenTripIndexLowerBound() {

        // Given the default pattern with the following trips: A and B
        pattern = new TestTripPattern(tripA, tripB);
        subject = new TripScheduleAlightSearch<>(TRIPS_THRESHOLD, pattern, this::skip);

        // Then we expect to find trip B when `tripIndexLowerBound` is A´s index
        assertTrip(1, TIME_B0, TIME_LATE, POS_0, 0);

        // An then no trip if `tripIndexLowerBound` equals last trip index
        assertNoTrip(TIME_LATE, POS_0, 2);
    }

    @Test
    public void findTripWithGivenTripIndexLowerBoundMixedWithSkippedTrips() {
        // Given the default pattern with the following trips: S, A, T, B

        // Then we expect to find trip A when `tripIndexLowerBound` is small enough
        assertTrip(TRIP_A_INDEX, TIME_A0, TIME_B0, POS_0, TRIP_A_INDEX);
        assertTrip(TRIP_A_INDEX, TIME_A1, TIME_B0, POS_1, TRIP_A_INDEX);

        // But NOT when `tripIndexLowerBound` equals trip B´s index + 1
        assertNoTrip(TIME_LATE, POS_0, TRIP_B_INDEX+1);
        assertNoTrip(TIME_LATE, POS_1, TRIP_B_INDEX+1);
    }

    @Test
    public void boardFirstAvailableTripForABigNumberOfTrips() {
        // For a pattern with N trip schedules, where the first trip departure is at time 100
        int n = TRIPS_THRESHOLD;
        int N = 3 * n + 7;

        List<TestTripSchedule> tripSchedules = new ArrayList<>();
        int arrivalTime = 0;

        for (int i = 0; i < N; i++) {
            arrivalTime += 100;
            tripSchedules.add(new TestTripSchedule(arrivalTime));
        }
        pattern = new TestTripPattern(tripSchedules);
        subject = new TripScheduleAlightSearch<>(TRIPS_THRESHOLD, pattern, this::skip);


        // Test some random boardings
        assertNoTrip(100, POS_0);
        int step = n/2 - 1;

        for (int i = 0; i < N; i += step) {
            int expArrivalTime = 100 * (i + 1);
            int latestAlightTime = expArrivalTime + 1;

            //assertTrip(i, expArrivalTime, latestAlightTime, POS_0);
            assertNoTrip(expArrivalTime, POS_0, i);
        }
    }

    @Test
    public void assertTripIsFoundEvenIfItIsAfterTheBinarySearchUpperBound() {
        final int N = TRIPS_THRESHOLD;

        // Given a pattern with n+1 trip schedules
        List<TestTripSchedule> tripSchedules = new ArrayList<>();


        // Where the N following trips are NOT in service, but with acceptable arrival times
        addNTimes(tripSchedules, tripS, N);

        // And where the last trip is in service
        tripSchedules.add(tripA);


        pattern = new TestTripPattern(tripSchedules);
        subject = new TripScheduleAlightSearch<>(TRIPS_THRESHOLD, pattern, this::skip);

        // Then we expect to find trip A, even if it is after the binary search window
        assertTrip(N, TIME_A0, TIME_A0 + 1, POS_0);
        assertTrip(N, TIME_A1, TIME_A1 + 1, POS_1);
    }

    @Test
    public void assertTripIsFoundEvenIfItIsBeforeTheBinarySearchLowerBound() {
        final int N = TRIPS_THRESHOLD;

        // Given a pattern with n+1 trip schedules
        List<TestTripSchedule> tripSchedules = new ArrayList<>();

        // Where the following trip is in service
        tripSchedules.add(tripA);

        // And here the N first trips are NOT in service, but with acceptable boarding times
        addNTimes(tripSchedules, tripT, N);

        pattern = new TestTripPattern(tripSchedules);
        subject = new TripScheduleAlightSearch<>(TRIPS_THRESHOLD, pattern, this::skip);

        // Then we expect to find trip A, even if it is after the binary search window
        assertTrip(0, TIME_A0, TIME_A0+1, POS_0);
        assertTrip(0, TIME_A1, TIME_A1+1, POS_1);
        assertNoTrip(TIME_A1, POS_1);
    }

    private <T extends TripScheduleInfo> boolean skip(T trip) {
        return trip == tripS || trip == tripT;
    }

    private void assertTrip(int expectedTripIndex, int expectedArrivalTime, int latestAlightTime, int stopPosition) {
        Assert.assertTrue("Trip found", subject.search(latestAlightTime, stopPosition));
        Assert.assertEquals("Trip index", expectedTripIndex, subject.getCandidateTripIndex());
        Assert.assertEquals("Board time", expectedArrivalTime, subject.getCandidateTrip().arrival(stopPosition));
    }

    private void assertTrip(int expectedTripIndex, int expectedArrivalTime, int latestAlightTime, int stopPosition, int tripIndexLowerBound) {
        Assert.assertTrue("Trip found", subject.search(latestAlightTime, stopPosition, tripIndexLowerBound));
        Assert.assertEquals("Trip index", expectedTripIndex, subject.getCandidateTripIndex());
        Assert.assertEquals("Board time", expectedArrivalTime, subject.getCandidateTrip().arrival(stopPosition));
    }

    private void assertNoTrip(int latestAlightTime, int stopPosition) {
        Assert.assertFalse(subject.search(latestAlightTime, stopPosition));
    }

    private void assertNoTrip(int latestAlightTime, int stopPosition, int tripIndexLowerBound) {
        Assert.assertFalse(subject.search(latestAlightTime, stopPosition, tripIndexLowerBound));
    }
    private static void addNTimes(List<TestTripSchedule> trips, TestTripSchedule tripS, int n) {
        for (int i = 0; i < n; i++) {
            trips.add(tripS);
        }
    }
}
