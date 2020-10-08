package com.conveyal.analysis.models;

import com.conveyal.r5.analyst.scenario.AddTrips;
import com.conveyal.r5.analyst.scenario.AdjustFrequency;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Convert a line to frequency.
 */
public class ConvertToFrequency extends Modification {
    public static final String type = "convert-to-frequency";
    @Override
    public String getType() {
        return "convert-to-frequency";
    }

    public String feed;
    public String[] routes;

    /** Should trips on this route that start outside the days/times specified by frequency entries be retained? */
    public boolean retainTripsOutsideFrequencyEntries = false;

    public List<FrequencyEntry> entries;

    public static class FrequencyEntry extends AbstractTimetable {
        /** start times of this trip (seconds since midnight), when non-null scheduled trips will be created */
        @Deprecated
        public int[] startTimes;

        /** trip from which to copy travel times */
        public String sourceTrip;

        /** trips on the selected patterns which could be used as source trips */
        public String[] patternTrips;

        public AddTrips.PatternTimetable toR5 (String feed) {
            AddTrips.PatternTimetable pt = toBaseR5Timetable();

            pt.sourceTrip = feed + ":" + sourceTrip;

            return pt;
        }
    }

    public AdjustFrequency toR5 () {
        AdjustFrequency af = new AdjustFrequency();
        af.comment = name;
        af.route = feedScopeId(feed, routes[0]);
        af.retainTripsOutsideFrequencyEntries = retainTripsOutsideFrequencyEntries;
        af.entries = entries.stream().map(e -> e.toR5(feed)).collect(Collectors.toList());

        return af;
    }
}
