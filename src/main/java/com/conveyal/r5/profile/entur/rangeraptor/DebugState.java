package com.conveyal.r5.profile.entur.rangeraptor;

import com.conveyal.r5.profile.entur.api.transit.TripScheduleInfo;
import com.conveyal.r5.profile.entur.rangeraptor.view.StopArrivalView;
import com.conveyal.r5.profile.entur.util.Debug;

import java.util.Arrays;
import java.util.List;

import static com.conveyal.r5.profile.entur.util.IntUtils.intToString;
import static com.conveyal.r5.profile.entur.util.TimeUtils.timeToStrCompact;
import static com.conveyal.r5.profile.entur.util.TimeUtils.timeToStrLong;


/**
 * To debug a particular journey set DEBUG to true and add all visited stops in the DEBUG_STOPS list.
 */
public final class DebugState {

    private static final List<Integer> DEBUG_STOPS = Arrays.asList();//45185,62028,16157,16426,39886,82455);
    private static final List<Integer> DEBUG_TRIP = Arrays.asList(45185,62028,16157,16426,39886,82455);
    private static final int DEBUG_TRIP_START = 16157;

    private static final String STOP_HEADER = "Description Rnd  From  To     Start    End        Time   Trip";
    private static final String LINE_FORMAT = " * %-8s %2d   %5s %5s  %8s %8s %8s  %6s";

    private static final int NOT_SET = Integer.MIN_VALUE;

    private static String title = "DEBUG";
    private static String headerPostfix = null;
    private static String lastTitle = null;


    /**
     * private constructor to prevent creating any instances of this utility class - static method only.
     */
    private DebugState() {
    }

    public static void init(boolean debug, List<Integer> debugStops) {
        if(debugStops == null || debugStops.isEmpty()) {
            Debug.setDebug(debug);
        }
        else {
            Debug.setDebug(true);
            DEBUG_STOPS.addAll(debugStops);
        }
    }

    public static void debugStopHeader(String title) {
        debugStopHeader(title, null);
    }

    public static boolean isDebug(int stop) {
        return Debug.isDebug() && DEBUG_STOPS.contains(stop);
    }

    public static void debugStopHeader(String newTitle, String newHeaderPostfix) {
        if (Debug.isDebug()) {
            title = newTitle;
            headerPostfix = newHeaderPostfix;
        }
    }

    public static <T extends TripScheduleInfo> void debugStop(StopArrivalView<T> state) {
        if (isDebug(state.stop())) {
            System.err.println(toString(state));
        }
    }

    public static <T extends TripScheduleInfo> void debugStop(StopArrivalView<T> state, String stopPostfix) {
        if (isDebug(state.stop())) {
            System.err.println(toString(state) + " | " + stopPostfix);
        }
    }

    private static <T extends TripScheduleInfo> String toString(StopArrivalView<T> state) {

        debugStopHeaderAtMostOnce();

        if (state.arrivedByTransit()) {
            assertTripNotNull(state);

            return format(
                    "Transit",
                    state.round(),
                    state.boardStop(),
                    state.stop(),
                    state.departureTime(),
                    state.arrivalTime(),
                    state.legDuration(),
                    state.trip()
            );

        }
        else if (state.arrivedByTransfer()) {
            return format(
                    "Walk",
                    state.round(),
                    state.transferFromStop(),
                    state.stop(),
                    state.departureTime(),
                    state.arrivalTime(),
                    state.legDuration(),
                    null
            );
        }
        else {
            return format(
                    "Access",
                    state.round(),
                    NOT_SET,
                    state.stop(),
                    state.departureTime(),
                    state.arrivalTime(),
                    NOT_SET,
                    null
            );
        }
    }

    private static <T extends TripScheduleInfo> String format(String description, int round, int fromStop, int toStop, int fromTime, int toTime, int dTime, T trip) {
        return String.format(
                LINE_FORMAT,
                description,
                round,
                intToString(fromStop, NOT_SET),
                toStop,
                timeToStrLong(fromTime, NOT_SET),
                timeToStrLong(toTime, NOT_SET),
                timeToStrCompact(dTime, NOT_SET),
                trip == null ? "" : trip.debugInfo()
        );
    }

    private static <T extends TripScheduleInfo> void assertSet(StopArrivalView<T> state, int value, int expectedDefault) {
        if (value != expectedDefault) {
            throw new IllegalStateException("Unexpected value in state: " + value + ", state: " + state);
        }
    }

    private static <T extends TripScheduleInfo> void assertNotSet(StopArrivalView<T> state, int value, int expectedDefault) {
        if (value == expectedDefault || value < 0) {
            throw new IllegalStateException("Unexpected value in state: " + value + ", state: " + state);
        }
    }

    private static <T extends TripScheduleInfo> void assertTripNotNull(StopArrivalView<T> state) {
        if (state.trip() == null) {
            throw new IllegalStateException("Unexpected 'null' value for trip. State: " + state);
        }
    }

    private static void debugStopHeaderAtMostOnce() {
        if (Debug.isDebug() && !title.equals(lastTitle)) {
            System.err.println("\n" + title);
            System.err.println(STOP_HEADER + (headerPostfix == null ? "" : " | " + headerPostfix));
            lastTitle = title;
        }
    }
}
