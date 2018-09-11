package com.conveyal.r5.profile.mcrr.util;

import com.conveyal.r5.profile.mcrr.StopState;

import java.util.ArrayList;
import java.util.List;

import static com.conveyal.r5.profile.mcrr.StopState.NOT_SET;
import static com.conveyal.r5.profile.mcrr.StopState.UNREACHED;
import static com.conveyal.r5.profile.mcrr.util.IntUtils.intToString;
import static com.conveyal.r5.profile.mcrr.util.TimeUtils.timeToStrCompact;
import static com.conveyal.r5.profile.mcrr.util.TimeUtils.timeToStrLong;


/**
 * To debug a particular journey set DEBUG to true and add all visited stops in the DEBUG_STOPS list.
 */
public final class DebugState {

    public enum Type {Access, Transfer, Transit}

    private static final List<Integer> DEBUG_STOPS = new ArrayList<>();

    private static final String STOP_HEADER = "Description Rnd  From  To     Start    End        Time   Pattern Trp";
    private static final String LINE_FORMAT = " * %-8s %2d   %5s %5s  %8s %8s %8s  %6s %3s";

    private static String title = "DEBUG";
    private static String headerPostfix = null;
    private static String lastTitle = null;


    /**
     * private constructor to prevent creating any instances of this utility class - static method only.
     */
    private DebugState() {
    }

    public static void init(List<Integer> debugStops) {
        if(debugStops == null) {
            Debug.setDebug(false);
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

    public static void debugStop(Type type, int round, int stop, StopState state) {
        if (isDebug(stop)) {
            System.err.println(toString(type, round, stop, state));
        }
    }

    public static void debugStop(Type type, int round, int stop, StopState state, String stopPostfix) {
        if (isDebug(stop)) {
            System.err.println(toString(type, round, stop, state) + " | " + stopPostfix);
        }
    }

    private static String toString(Type type, int round, int stopIndex, StopState state) {
        debugStopHeaderAtMostOnce();
        if (type == Type.Access) {
            assertSet(state, state.boardTime(), UNREACHED);
            assertSet(state, state.boardStop(), NOT_SET);
            assertSet(state, state.transferTime(), NOT_SET);
            assertSet(state, state.transferFromStop(), NOT_SET);
            assertSet(state, state.pattern(), NOT_SET);
            assertSet(state, state.trip(), NOT_SET);

            return format(
                    "Access",
                    round,
                    NOT_SET,
                    stopIndex,
                    UNREACHED,
                    state.time(),
                    NOT_SET,
                    NOT_SET,
                    NOT_SET
            );
        }
        else if (type == Type.Transit) {
            assertNotSet(state, state.boardTime(), UNREACHED);
            assertNotSet(state, state.boardStop(), NOT_SET);
            assertNotSet(state, state.pattern(), NOT_SET);
            assertNotSet(state, state.trip(), NOT_SET);
            return format(
                    "Transit",
                    round,
                    state.boardStop(),
                    stopIndex,
                    state.boardTime(),
                    state.transitTime(),
                    state.transitTime() - state.boardTime(),
                    state.pattern(),
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
                    NOT_SET,
                    NOT_SET
            );
        }
        throw new IllegalArgumentException("Type not supported: " + type);
    }

    private static String format(String description, int round, int fromStop, int toStop, int fromTime, int toTime, int dTime, int pattern, int trip) {
        return String.format(
                LINE_FORMAT,
                description,
                round,
                intToString(fromStop, NOT_SET),
                toStop,
                timeToStrLong(fromTime, UNREACHED),
                timeToStrLong(toTime, UNREACHED),
                timeToStrCompact(dTime, NOT_SET),
                intToString(pattern, NOT_SET),
                intToString(trip, NOT_SET)
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

    private static void debugStopHeaderAtMostOnce() {
        if (Debug.isDebug() && !title.equals(lastTitle)) {
            System.err.println("\n" + title);
            System.err.println(STOP_HEADER + (headerPostfix == null ? "" : " | " + headerPostfix));
            lastTitle = title;
        }
    }
}
