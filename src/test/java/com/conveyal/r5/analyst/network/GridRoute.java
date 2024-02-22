package com.conveyal.r5.analyst.network;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Represents a single transit route on a grid, used to create networks with predictable characteristics in tests.
 */
public class GridRoute {

    public GridLayout gridLayout;

    public String id;
    public int startX;
    public int startY;
    public int nStops;
    public int stopSpacingBlocks;
    public Orientation orientation;
    public boolean bidirectional;

    /** Explicit departure times from first stop; if set, startHour and endHour will be ignored. */
    public int[] startTimes;

    /** Override default hop times. Map of (trip, stopAtStartOfHop) to factor by which default hop is multiplied. */
    public Map<TripHop, Double> hopTimeScaling;

    /** These will be services codes, and can be referenced in timetables. */
    public static enum Services { ALL, WEEKDAY, WEEKEND }

    /** How a Timetable will be translated into GTFS data - stop_times or frequencies with or without exact_times. */
    public static enum Materialization { STOP_TIMES, PURE_FREQ, EXACT_TIMES }

    /** All Timetables on a GridRoute will be materialized in the same way, according to this field. */
    public Materialization materialization = Materialization.STOP_TIMES;

    /** This defines something like a frequency in GTFS, but can also be used to generate normal stop_times trips. */
    public static class Timetable {
        Services service;
        public int startHour;
        public int endHour;
        int headwayMinutes;
    }

    public List<Timetable> timetables = new ArrayList<>();

    private Stream<String> stopIds() {
        return null;
    }

    public static enum Orientation { HORIZONTAL, VERTICAL }

    public int nBlocksLength () {
        return (nStops - 1) * stopSpacingBlocks;
    }

    public int getStopX (int stop) {
        int stopX = startX;
        if (orientation == Orientation.HORIZONTAL) {
            stopX += stopSpacingBlocks * stop;
        }
        return stopX;
    }

    public int getStopY (int stop) {
        int stopY = startY;
        if (orientation == Orientation.VERTICAL) {
            stopY += stopSpacingBlocks * stop;
        }
        return stopY;
    }

    public double getStopLat (int stop) {
        return gridLayout.getIntersectionLat(getStopY(stop));
    }

    public double getStopLon (int stop) {
        return gridLayout.getIntersectionLon(getStopX(stop), getStopLat(stop));
    }

    public String stopId (int stop, boolean mergeStops) {
        if (mergeStops) {
            return String.format("X%dY%d", getStopX(stop), getStopY(stop));
        } else {
            return id + stop;
        }
    }

    public double hopTime (TripHop tripHop) {
        double scale;
        if (hopTimeScaling != null && hopTimeScaling.get(tripHop) != null) {
            scale = hopTimeScaling.get(tripHop);
        } else {
            scale = 1;
        }
        return scale * stopSpacingBlocks * gridLayout.transitBlockTraversalTimeSeconds;
    }

    private static GridRoute newBareRoute (GridLayout gridLayout, int headwayMinutes) {
        GridRoute route = new GridRoute();
        route.id = gridLayout.nextIntegerId(); // Avoid collisions when same route is added multiple times
        route.stopSpacingBlocks = 1;
        route.gridLayout = gridLayout;
        route.bidirectional = true;
        route.nStops = gridLayout.widthAndHeightInBlocks + 1;
        route.addTimetable(Services.WEEKDAY, 5, 10, headwayMinutes);
        return route;
    }

    public static GridRoute newHorizontalRoute (GridLayout gridLayout, int row, int headwayMinutes) {
        GridRoute route = newBareRoute(gridLayout, headwayMinutes);
        route.orientation = Orientation.HORIZONTAL;
        route.startX = 0;
        route.startY = row;
        // route.id = "H" + row;
        return route;
    }

    public GridRoute pureFrequency () {
        this.materialization = Materialization.PURE_FREQ;
        return this;
    }

    public GridRoute addTimetable (Services service, int startHour, int endHour, int headwayMinutes) {
        Timetable timetable = new Timetable();
        timetable.service = service;
        timetable.startHour = startHour;
        timetable.endHour = endHour;
        timetable.headwayMinutes = headwayMinutes;
        this.timetables.add(timetable);
        return this;
    }

    public static GridRoute newVerticalRoute (GridLayout gridLayout, int col, int headwayMinutes) {
        GridRoute route = newBareRoute(gridLayout, headwayMinutes);
        route.orientation = Orientation.VERTICAL;
        route.startX = col;
        route.startY = 0;
        // route.id = "V" + col;
        return route;
    }

    public static class TripHop {
        int trip;
        int hop;

        public TripHop(int trip, int hop){
            this.trip = trip;
            this.hop = hop;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            TripHop tripHop = (TripHop) o;
            return trip == tripHop.trip &&
                    hop == tripHop.hop;
        }

        @Override
        public int hashCode() {
            return Objects.hash(trip, hop);
        }
    }

}
