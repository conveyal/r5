package com.conveyal.analysis.models;

import com.conveyal.analysis.AnalysisServerException;
import com.conveyal.r5.analyst.scenario.StopSpec;
import com.conveyal.r5.util.ExceptionUtils;
import org.geotools.geometry.jts.JTS;
import org.geotools.referencing.crs.DefaultGeographicCRS;
import org.locationtech.jts.geom.Coordinate;
import org.opengis.referencing.crs.CoordinateReferenceSystem;
import org.opengis.referencing.operation.TransformException;

import java.util.ArrayList;
import java.util.List;
import java.util.Stack;
import java.util.stream.Collectors;

/**
 * An intermediate step between the front-end representation of new or extended trips and the form that R5 expects.
 */
class ModificationStop {
    private static final CoordinateReferenceSystem crs = DefaultGeographicCRS.WGS84;

    // General Constants
    private static double MIN_SPACING_PERCENTAGE = 0.25;
    private static int DEFAULT_SEGMENT_SPEED_KPH = 15;

    // Conversion Factors
    public static double SECONDS_PER_HOUR = 60 * 60;
    public static double METERS_PER_KM = 1000;

    /** Internal stop spec representation. */
    private final StopSpec stopSpec;

    /** Dwell time at the stop */
    private final int dwellTimeSeconds;

    /** Seconds between stops, updated and reset while traveling down the pattern. */
    private final int hopTimeSeconds;

    /** True if the stop was one of the ones generated automatically at regular intervals along the new route. */
    private final boolean autoGenerated;

    /**
     * Constructor for ModificationStops. It creates an immutable object with all fields final.
     */
    private ModificationStop (StopSpec stopSpec, int dwellTimeSeconds, int hopTimeSeconds, boolean autoGenerated) {
        this.stopSpec = stopSpec;
        this.dwellTimeSeconds = dwellTimeSeconds;
        this.hopTimeSeconds = hopTimeSeconds;
        this.autoGenerated = autoGenerated;
    }

    /**
     * Convert a list of Segments from a modification (the front-end representation) to this internal
     * backend representation.
     */
    static List<ModificationStop> getStopsFromSegments(List<Segment> segments, List<Integer> dwellTimes, int defaultDwellTime, List<Integer> segmentSpeedsKph) {
        if (segments == null || segments.size() == 0) {
            return new ArrayList<>();
        }

        // Keep a stack of Stops because as part of auto-generating stops we sometimes need to back one out.
        Stack<ModificationStop> stops = new Stack<>();

        Segment firstSegment = segments.get(0);
        int realStopIndex = 0;

        if (firstSegment.stopAtStart) {
            Coordinate c = firstSegment.geometry.getCoordinates()[0];
            StopSpec stopSpec = new StopSpec(c.x, c.y);
            if (firstSegment.fromStopId != null) {
                stopSpec = new StopSpec(firstSegment.fromStopId);
            }

            int dwellTime = dwellAtStop(realStopIndex, defaultDwellTime, dwellTimes);

            realStopIndex++;

            stops.add(new ModificationStop(stopSpec, dwellTime, 0, false));
        }

        double metersToLastStop = 0; // distance to previously created stop, from start of pattern
        double metersFromPatternStart = 0; // from start of pattern
        int hopTimeSeconds = 0;
        int previousSegmentSpeedKph = segmentSpeedsKph.size() > 0 ? segmentSpeedsKph.get(0) : DEFAULT_SEGMENT_SPEED_KPH;
        Coordinate previousSegmentFinalCoordinate = null;

        // Iterate over segments, which are defined by control points and real (i.e. not auto-generated at a requested
        // spacing) stops.
        for (int i = 0; i < segments.size(); i++) {
            Segment segment = segments.get(i);

            // A timetable's segment speeds array may get out of sync with the
            int segmentSpeedKph = i < segmentSpeedsKph.size() ? segmentSpeedsKph.get(i) : previousSegmentSpeedKph;
            double segmentSpeedMps = segmentSpeedKph * METERS_PER_KM / SECONDS_PER_HOUR;

            // Set the previous segment speed to the current one since we just use the `mps` later
            previousSegmentSpeedKph = segmentSpeedKph;

            int stopSpacing = segment.spacing;
            boolean autoCreateStops = stopSpacing > 0;

            Coordinate[] coords = segment.geometry.getCoordinates();
            if (previousSegmentFinalCoordinate != null && !coords[0].equals2D(previousSegmentFinalCoordinate)) {
                throw AnalysisServerException.unknown("Start of segment " + i + 1 + " not at end of previous segment:" +
                        " " + previousSegmentFinalCoordinate + " and " + coords[0]);
            }

            // Iterate over the coordinates of the segment geometry (which is generally a LineString).
            for (int j = 1; j < coords.length; j++) {
                Coordinate c0 = coords[j - 1];
                Coordinate c1 = coords[j];
                double lineSegmentMeters;
                try {
                    // JTS orthodromic distance returns meters, considering the input coordinate system.
                    lineSegmentMeters = JTS.orthodromicDistance(c0, c1, crs);
                } catch (TransformException e) {
                    throw AnalysisServerException.unknown(ExceptionUtils.stackTraceString(e));
                }
                double metersAtEndOfSegment = metersFromPatternStart + lineSegmentMeters;

                if (autoCreateStops) {
                    // Auto-create stops while this segment includes at least one point that is more than 'spacing'
                    // meters farther along the pattern than the previously created stop.
                    while (metersToLastStop + stopSpacing < metersAtEndOfSegment) {
                        double frac = (metersToLastStop + stopSpacing - metersFromPatternStart)
                                / lineSegmentMeters;
                        if (frac < 0) {
                            frac = 0;
                        }
                        Coordinate c = new Coordinate(c0.x + (c1.x - c0.x) * frac, c0.y + (c1.y - c0.y) * frac);

                        // We can't just add segment.spacing because of converting negative fractions to zero above.
                        // This can happen when the last segment did not have automatic stop creation, or had a larger
                        // spacing. TODO in the latter case, we probably want to continue to apply the spacing from the
                        // last line segment until we create a new stop?
                        double metersToAutoCreatedStop = metersFromPatternStart + frac * lineSegmentMeters;

                        // Add the hop time
                        if (metersFromPatternStart > metersToLastStop) {
                            hopTimeSeconds += (int) ((metersToAutoCreatedStop - metersFromPatternStart) / segmentSpeedMps);
                        } else {
                            hopTimeSeconds += (int) ((metersToAutoCreatedStop - metersToLastStop) / segmentSpeedMps);
                        }

                        // Create the stop spec
                        StopSpec stopSpec = new StopSpec(c.x, c.y);

                        // Add the auto-created stop to the stack
                        stops.add(new ModificationStop(stopSpec, defaultDwellTime, hopTimeSeconds, true));

                        // Set the distance to the last stop
                        metersToLastStop = metersToAutoCreatedStop;

                        // Reset the hop time
                        hopTimeSeconds = 0;
                    }
                }

                // Add the hop time for the rest of the segment or the entire segment if no auto-created stops were added.
                if (metersFromPatternStart > metersToLastStop) {
                    hopTimeSeconds += (int) (lineSegmentMeters / segmentSpeedMps);
                } else {
                    hopTimeSeconds += (int) ((metersAtEndOfSegment - metersToLastStop) / segmentSpeedMps);
                }

                metersFromPatternStart = metersAtEndOfSegment;
            }

            if (segment.stopAtEnd) {
                // If the last auto-generated stop was too close to a manually created stop (other than the first stop),
                // remove it.
                if (autoCreateStops && stops.size() > 1) {
                    ModificationStop lastStop = stops.peek();
                    double spacingPercentage = (metersFromPatternStart - metersToLastStop) / stopSpacing;
                    if (lastStop.autoGenerated && spacingPercentage < MIN_SPACING_PERCENTAGE) {
                        // Remove the stop and reset the distance to the last stop
                        stops.pop();
                        hopTimeSeconds += lastStop.hopTimeSeconds;
                    }
                }

                Coordinate endCoord = coords[coords.length - 1];
                StopSpec stopSpec = new StopSpec(endCoord.x, endCoord.y);
                if (segment.toStopId != null) {
                    stopSpec = new StopSpec(segment.toStopId);
                }

                int dwellTime = dwellAtStop(realStopIndex, defaultDwellTime, dwellTimes);

                stops.add(new ModificationStop(stopSpec, dwellTime, hopTimeSeconds, false));

                // Set the distance to the last stop
                metersToLastStop = metersFromPatternStart;

                // Reset the hop time
                hopTimeSeconds = 0;

                // A real (not auto-generated) stop has been added.
                realStopIndex++;
            }

            // Set the coordinate for sanity checking that the next segment starts at the current segment's end.
            previousSegmentFinalCoordinate = coords[coords.length - 1];
        }

        return new ArrayList<>(stops);
    }

    /**
     * Convert a list of ModificationStops (which are internal to the backend conversion process) to a list of the
     * StopSpec type required by r5.
     */
    static List<StopSpec> toStopSpecs (List<ModificationStop> stops) {
        return stops.stream()
                .map(s -> s.stopSpec)
                .collect(Collectors.toList());
    }

    static int[] getDwellTimes (List<ModificationStop> stops) {
        if (stops == null || stops.size() == 0) {
            return new int[0];
        }

        int[] dwellTimes = new int[stops.size()];
        for (int i = 0; i < stops.size(); i++) dwellTimes[i] = stops.get(i).dwellTimeSeconds;

        return dwellTimes;
    }

    static int dwellAtStop(int realStopIndex, int defaultDwellTime, List<Integer> dwellTimes) {
        // If a dwell time for this stop has been specified explicitly, use it
        if (dwellTimes != null && dwellTimes.size() > realStopIndex && dwellTimes.get(realStopIndex) != null) {
            return dwellTimes.get(realStopIndex);
        } else {
            return defaultDwellTime;
        }
    }

    static int[] getHopTimes (List<ModificationStop> stops) {
        if (stops == null || stops.size() < 2) {
            return new int[0];
        }

        // First stop has no hop.
        int[] hopTimes = new int[stops.size() - 1];
        for (int i = 1; i < stops.size(); i++) hopTimes[i - 1] = stops.get(i).hopTimeSeconds;

        return hopTimes;
    }

}
