package com.conveyal.r5.speed_test;


import com.conveyal.r5.profile.entur.api.debug.DebugEvent;
import com.conveyal.r5.profile.entur.api.path.Path;
import com.conveyal.r5.profile.entur.rangeraptor.path.PathMapper;
import com.conveyal.r5.profile.entur.rangeraptor.view.DestinationArrivalView;
import com.conveyal.r5.profile.entur.rangeraptor.view.StopArrivalView;
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
    private final PathMapper<TripSchedule> pathMapper;

    DebugLogger(PathMapper<TripSchedule>pathMapper) {
        this.pathMapper = pathMapper;
    }

    void stopArrivalLister(DebugEvent<StopArrivalView<TripSchedule>> e) {

        printIterationHeader(e.iterationStartTime());
        printRoundHeader(e.element().round());
        print(e.element(), e.action().toString());

        if (e.action() == DebugEvent.Action.DROP && e.droppedByElement() != null) {
            print(e.droppedByElement(), "->by");
        }
    }

    void destinationArrivalListener(DebugEvent<DestinationArrivalView<TripSchedule>> e) {
        DestinationArrivalView<TripSchedule> d = e.element();
        int round = d.previous().round();
        int cost = d.cost();
        Path<TripSchedule> path = pathMapper.mapToPath(d);

        printIterationHeader(e.iterationStartTime());
        printRoundHeader(round);

        print(
                e.action().toString(),
                round,
                "Egress",
                path.egressLeg().fromStop(),
                path.endTime(),
                cost,
                "",
                path.toString()
        );
    }

    void pathFilteringListener(DebugEvent<Path<TripSchedule>> e) {
        if (pathHeader) {
            System.err.println();
            System.err.println("PATH   | TR | FROM  | TO    | START    | END      | DURATION");
            pathHeader = false;
        }

        Path<TripSchedule> p = e.element();
        System.err.printf(
                "%6s | %2d | %5d | %5d | %8s | %8s | %8s %n",
                center(e.action().toString(), 6),
                p.numberOfTransfers(),
                p.accessLeg().toStop(),
                p.egressLeg().fromStop(),
                TimeUtils.timeToStrLong(p.accessLeg().fromTime()),
                TimeUtils.timeToStrLong(p.egressLeg().toTime()),
                timeToStrCompact(p.totalTravelDurationInSeconds())
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

    private void print(StopArrivalView<?> a, String action) {
        String trip = a.arrivedByTransit() ? a.trip().debugInfo() : "";
        print(action, a.round(), a.legType(), a.stop(), a.arrivalTime(), a.cost(), trip, path(a));
    }

    private String path(StopArrivalView<?> a) {
        return path(a, new PathStringBuilder()).toString();
    }

    private PathStringBuilder path(StopArrivalView<?> a, PathStringBuilder buf) {
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
    private int legDuration(StopArrivalView<?> a) {
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
                "%-6s | %-8s | %3s | %-5s | %-8s | %4s | %-20s | %s %n",
                "ACTION",
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
                "%-6s | %-8s | %2d  | %5s | %8s | %4d | %-20s | %s %n",
                center(action, 6),
                center(leg, 8),
                round,
                IntUtils.intToString(toStop, NOT_SET),
                TimeUtils.timeToStrLong(toTime),
                cost,
                trip,
                details
        );
    }

    private String center(String text, int columnWidth) {
        return StringUtils.center(text, columnWidth);
    }
}
