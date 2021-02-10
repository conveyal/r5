package com.conveyal.r5.analyst.network;

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
    public int startHour;
    public int endHour;
    public int headwayMinutes;
    public boolean pureFrequency;

    private Stream<String> stopIds() {
        return null;
    }

    public static enum Orientation {
        HORIZONTAL, VERTICAL
    }

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

    private static GridRoute newBareRoute (GridLayout gridLayout, int headwayMinutes) {
        GridRoute route = new GridRoute();
        route.id = gridLayout.nextIntegerId(); // Avoid collisions when same route is added multiple times
        route.stopSpacingBlocks = 1;
        route.gridLayout = gridLayout;
        route.startHour = 5;
        route.endHour = 10;
        route.bidirectional = true;
        route.headwayMinutes = headwayMinutes;
        route.nStops = gridLayout.widthAndHeightInBlocks + 1;
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

    public static GridRoute newVerticalRoute (GridLayout gridLayout, int col, int headwayMinutes) {
        GridRoute route = newBareRoute(gridLayout, headwayMinutes);
        route.orientation = Orientation.VERTICAL;
        route.startX = col;
        route.startY = 0;
        // route.id = "V" + col;
        return route;
    }

    public GridRoute pureFrequency () {
        pureFrequency = true;
        return this;
    }

}
