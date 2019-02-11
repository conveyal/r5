package com.conveyal.r5.profile.entur.api;

import com.conveyal.r5.profile.entur.api.transit.TripScheduleInfo;

import java.util.Arrays;

public class TestTripSchedule implements TripScheduleInfo {
    public static final int DEPARTURE_DELAY = 10;
    private final int[] arrivalTimes;

    public static TestTripSchedule createTripScheduleUseingArrivalTimes(int ... arrivalTimes) {
        return new TestTripSchedule(arrivalTimes);
    }

    public static TestTripSchedule createTripScheduleUseingDepartureTimes(int ... departureTimes) {
        int[] arrivalTimes = Arrays.copyOf(departureTimes, departureTimes.length);
        for (int i = 0; i < arrivalTimes.length; i++) {
            arrivalTimes[i] -= TestTripSchedule.DEPARTURE_DELAY;
        }
        return new TestTripSchedule(arrivalTimes);
    }

    private TestTripSchedule(int ... arrivalTimes) {
        this.arrivalTimes = arrivalTimes;
    }

    @Override
    public int arrival(int stopPosInPattern) {
        return arrivalTimes[stopPosInPattern];
    }

    @Override
    public int departure(int stopPosInPattern) {
        return arrivalTimes[stopPosInPattern] + DEPARTURE_DELAY;
    }

    @Override
    public String debugInfo() {
        return Arrays.toString(arrivalTimes);
    }

    public int size() {
        return arrivalTimes.length;
    }
}
