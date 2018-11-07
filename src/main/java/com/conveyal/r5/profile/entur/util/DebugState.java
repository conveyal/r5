package com.conveyal.r5.profile.entur.util;

import com.conveyal.r5.profile.entur.api.TripScheduleInfo;
import com.conveyal.r5.profile.entur.rangeraptor.standard.StopState;

import java.util.ArrayList;
import java.util.List;

import static com.conveyal.r5.profile.entur.rangeraptor.standard.StopState.NOT_SET;
import static com.conveyal.r5.profile.entur.rangeraptor.standard.StopState.UNREACHED;
import static com.conveyal.r5.profile.entur.util.IntUtils.intToString;
import static com.conveyal.r5.profile.entur.util.TimeUtils.timeToStrCompact;
import static com.conveyal.r5.profile.entur.util.TimeUtils.timeToStrLong;


/**
 * To debug a particular journey set DEBUG to true and add all visited stops in the DEBUG_STOPS list.
 */
public final class DebugState {

    public enum Type {Access, Transfer, Transit}

    private static final List<Integer> DEBUG_STOPS = new ArrayList<>();

    private static final String STOP_HEADER = "Description Rnd  From  To     Start    End        Time   Trip";
    private static final String LINE_FORMAT = " * %-8s %2d   %5s %5s  %8s %8s %8s  %6s";

    private static String title = "DEBUG";
    private static String headerPostfix = null;
    private static String lastTitle = null;


    /**
     * private constructor to prevent creating any instances of this utility class - static method only.
     */
    private DebugState() {
    }

    public static void init(boolean debug, List<Integer> debugStops) {
        if(debugStops == null) {
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

    public static <T extends TripScheduleInfo> void debugStop(Type type, int round, int stop, StopState<T> state) {
        if (isDebug(stop)) {
            System.err.println(toString(type, round, stop, state));
        }
    }

    public static <T extends TripScheduleInfo> void debugStop(Type type, int round, int stop, StopState<T> state, String stopPostfix) {
        if (isDebug(stop)) {
            System.err.println(toString(type, round, stop, state) + " | " + stopPostfix);
        }
    }

    private static <T extends TripScheduleInfo> String toString(Type type, int round, int stopIndex, StopState<T> state) {
        debugStopHeaderAtMostOnce();
        if (type == Type.Access) {
            assertSet(state, state.boardTime(), UNREACHED);
            assertSet(state, state.boardStop(), NOT_SET);
            assertSet(state, state.transferTime(), NOT_SET);
            assertSet(state, state.transferFromStop(), NOT_SET);
            assertNotNull(state, state.trip(), "trip");

            return format(
                    "Access",
                    round,
                    NOT_SET,
                    stopIndex,
                    UNREACHED,
                    state.time(),
                    NOT_SET,
                    null
            );
        }
        else if (type == Type.Transit) {
            assertNotSet(state, state.boardTime(), UNREACHED);
            assertNotSet(state, state.boardStop(), NOT_SET);
            assertNull(state, state.trip(), "trip");
            return format(
                    "Transit",
                    round,
                    state.boardStop(),
                    stopIndex,
                    state.boardTime(),
                    state.transitTime(),
                    state.transitTime() - state.boardTime(),
                    state.trip()
            );

        }
        else if (type == Type.Transfer) {
            assertNotSet(state, state.transferTime(), NOT_SET);
            assertNotSet(state, state.transferFromStop(), NOT_SET);
            return format(
                    "Walk",
                    round,
                    state.transferFromStop(),
                    stopIndex,
                    state.time() - state.transferTime(),
                    state.time(),
                    state.transferTime(),
                    null
            );
        }
        throw new IllegalArgumentException("Type not supported: " + type);
    }

    private static <T extends TripScheduleInfo> String format(String description, int round, int fromStop, int toStop, int fromTime, int toTime, int dTime, T trip) {
        return String.format(
                LINE_FORMAT,
                description,
                round,
                intToString(fromStop, NOT_SET),
                toStop,
                timeToStrLong(fromTime, UNREACHED),
                timeToStrLong(toTime, UNREACHED),
                timeToStrCompact(dTime, NOT_SET),
                trip == null ? "" : trip.debugInfo()
        );
    }

    private static void assertSet(StopState state, int value, int expectedDefault) {
        if (value != expectedDefault) {
            throw new IllegalStateException("Unexpected value in state: " + value + ", state: " + state);
        }
    }

    private static void assertNotSet(StopState state, int value, int expectedDefault) {
        if (value == expectedDefault || value < 0) {
            throw new IllegalStateException("Unexpected value in state: " + value + ", state: " + state);
        }
    }

    private static <T> void assertNotNull(StopState state, T value, String name) {
        if (value == null) {
            throw new IllegalStateException("Unexpected null value in state. " + name + " is null, state: " + state);
        }
    }

    private static <T> void assertNull(StopState state, T value, String name) {
        if (value != null) {
            throw new IllegalStateException("Unexpected value in state, expected null. " + name + " is not null.  state: " + state);
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
