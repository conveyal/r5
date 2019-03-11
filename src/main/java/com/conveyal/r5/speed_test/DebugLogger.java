package com.conveyal.r5.speed_test;


import com.conveyal.r5.profile.entur.api.debug.DebugEvent;
import com.conveyal.r5.profile.entur.api.path.Path;
import com.conveyal.r5.profile.entur.api.view.ArrivalView;
import com.conveyal.r5.profile.entur.util.IntUtils;
import com.conveyal.r5.profile.entur.util.PathStringBuilder;
import com.conveyal.r5.profile.entur.util.TimeUtils;
import com.conveyal.r5.transit.TripSchedule;
import org.apache.commons.lang.StringUtils;

import static com.conveyal.r5.profile.entur.util.TimeUtils.timeToStrCompact;

class DebugLogger {
    private int NOT_SET = Integer.MIN_VALUE;
    private int lastIterationTime = NOT_SET;
    private int lastRound = NOT_SET;

    private boolean pathHeader = true;

    void stopArrivalLister(DebugEvent<ArrivalView<TripSchedule>> e) {

        printIterationHeader(e.iterationStartTime());
        printRoundHeader(e.element().round());
        print(e.element(), e.action().toString(), e.reason());

        ArrivalView<TripSchedule> byElement = e.rejectedDroppedByElement();
        if (e.action() == DebugEvent.Action.DROP && byElement != null) {
            print(byElement, "->by", "");
        }
    }

    void pathFilteringListener(DebugEvent<Path<TripSchedule>> e) {
        if (pathHeader) {
            System.err.println();
            System.err.println("* PATH *  | TR | FROM  | TO    | START    | END      | DURATION |   COST   | DETAILS");
            pathHeader = false;
        }

        Path<TripSchedule> p = e.element();
        System.err.printf(
                "%9s | %2d | %5d | %5d | %8s | %8s | %8s | %8s | %s%n",
                center(e.action().toString(), 9),
                p.numberOfTransfers(),
                p.accessLeg().toStop(),
                p.egressLeg().fromStop(),
                TimeUtils.timeToStrLong(p.accessLeg().fromTime()),
                TimeUtils.timeToStrLong(p.egressLeg().toTime()),
                timeToStrCompact(p.totalTravelDurationInSeconds()),
                p.cost(),
                concat(e.reason(), e.toString())
        );
    }

    /* private methods */

    private void printIterationHeader(int iterationTime) {
        if (iterationTime == lastIterationTime) return;
        lastIterationTime = iterationTime;
        lastRound = NOT_SET;
        pathHeader = true;
        System.err.println("\n**  RUN RAPTOR FOR MINUTE: " + timeToStrCompact(iterationTime) + "  **");
    }

    private void print(ArrivalView<?> a, String action, String optReason) {
        String trip = a.arrivedByTransit() ? a.trip().debugInfo() : "";
        print(action, a.round(), a.legType(), a.stop(), a.arrivalTime(), a.cost(), trip, concat(optReason, path(a)));
    }

    private static String path(ArrivalView<?> a) {
        return path(a, new PathStringBuilder()).toString();
    }

    private static PathStringBuilder path(ArrivalView<?> a, PathStringBuilder buf) {
        if (a.arrivedByAccessLeg()) {
            return buf.walk(legDuration(a)).sep().stop(a.stop());
        }
        // Recursively call this method to insert arrival in front of this arrival
        path(a.previous(), buf);

        buf.sep();

        if (a.arrivedByTransit()) {
            buf.transit(a.departureTime(), a.arrivalTime());
        } else {
            buf.walk(legDuration(a));
        }
        return buf.sep().stop(a.stop());
    }

    /**
     * The absolute time duration in seconds of a trip.
     */
    private static int legDuration(ArrivalView<?> a) {
        // Depending on the search direction this may or may not be negative, if we
        // search backwards in time then we arrive before we depart ;-) Hence
        // we need to use the absolute value.
        return Math.abs(a.arrivalTime() - a.departureTime());
    }

    private void printRoundHeader(int round) {
        if (round == lastRound) return;
        lastRound = round;

        System.err.println();
        System.err.printf(
                "%-9s | %-8s | %3s | %-5s | %-8s | %8s | %-20s | %s %n",
                "ARRIVAL",
                center("LEG", 8),
                "RND",
                "STOP",
                "ARRIVE",
                "COST",
                "TRIP",
                "DETAILS"
        );
    }

    private void print(String action, int round, String leg, int toStop, int toTime, int cost, String trip, String details) {
        System.err.printf(
                "%-9s | %-8s | %2d  | %5s | %8s | %8d | %-20s | %s %n",
                center(action, 9),
                center(leg, 8),
                round,
                IntUtils.intToString(toStop, NOT_SET),
                TimeUtils.timeToStrLong(toTime),
                cost,
                trip,
                details
        );
    }

    private static String center(String text, int columnWidth) {
        return StringUtils.center(text, columnWidth);
    }

    private static String concat(String s, String t) {
        if(s == null || s.isEmpty()) {
            return t == null ? "" : t;
        }
        return s + " " + (t == null ? "" : t);
    }
}
