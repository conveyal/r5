// in transit package so we can set package-private variables in TripPatternKey
package com.conveyal.r5.transit;

import com.conveyal.gtfs.model.Frequency;
import com.conveyal.gtfs.model.Trip;
import com.conveyal.r5.profile.FrequencyRandomOffsets;
import com.conveyal.r5.transit.TransitLayer;
import com.conveyal.r5.transit.TripPattern;
import com.conveyal.r5.transit.TripPatternKey;
import gnu.trove.list.array.TIntArrayList;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.*;

/**
 * Test that phasing works correctly.
 */
public class FrequencyRandomOffsetsTest {
    @Test
    public void testPhasing () {
        // make a fake transit layer
        TransitLayer layer = new TransitLayer();
        layer.hasFrequencies = true;
        layer.hasSchedules = false;

        // create two frequency-based lines
        TripPatternKey key1 = new TripPatternKey("route1");
        key1.stops = new TIntArrayList(new int[] { 1, 2, 3, 4 });

        // java initializes everything to 0, and pickup type 0 is normal service
        key1.pickupTypes = new TIntArrayList(new int[4]);
        key1.dropoffTypes = new TIntArrayList(new int[4]);

        TripPattern pattern1 = new TripPattern(key1);
        Trip t1 = new Trip();
        Frequency f1 = new Frequency();
        f1.start_time = 5 * 60 * 60;
        f1.end_time = 11 * 60 * 60;
        f1.headway_secs = 30 * 60;
        f1.exact_times = 0;
        t1.frequencies = Arrays.asList(f1);
        TripSchedule ts1 = TripSchedule.create(t1, new int [] { 0, 120, 240, 360 }, new int [] { 0, 120, 240, 360 }, new int[] { 1, 2, 3, 4 }, 0);
        pattern1.addTrip(ts1);

        layer.tripPatterns.add(pattern1);

        TripPatternKey key2 = new TripPatternKey("route1");
        key2.stops = new TIntArrayList(new int[] { 5, 6, 7, 8 });

        // java initializes everything to 0, and pickup type 0 is normal service
        key2.pickupTypes = new TIntArrayList(new int[4]);
        key2.dropoffTypes = new TIntArrayList(new int[4]);

        TripPattern pattern2 = new TripPattern(key2);
        Trip t2 = new Trip();
        Frequency f2 = new Frequency();
        // use a different start time to ensure that that is taken into account
        f2.start_time = 5 * 60 * 60 + 8 * 60;
        f2.end_time = 11 * 60 * 60;
        f2.headway_secs = 30 * 60;
        f2.exact_times = 0;
        t2.frequencies = Arrays.asList(f2);
        TripSchedule ts2 = TripSchedule.create(t2, new int [] { 0, 60, 90, 180 }, new int [] { 0, 60, 90, 180 }, new int[] { 1, 2, 3, 4 }, 0);

        ts2.phasedAtTargetStopPosition = new int[] { 1 };
        ts2.phasedFromSourceStopPosition = new int[] { 2 };
        ts2.phasedFromFrequencyEntry = new int[] { 0 };
        ts2.phasedFromPattern = new int [] { 0 };
        ts2.phasedFromTrip = new int [] { 0 };
        ts2.phaseSeconds = new int [] { 10 * 60 };

        pattern2.addTrip(ts2);

        layer.tripPatterns.add(pattern2);

        FrequencyRandomOffsets fro = new FrequencyRandomOffsets(layer);
        fro.randomize();

        // check that phasing is correct
        // offset indices are trip pattern, trip, frequency entry
        int timeAtTargetStop = ts2.startTimes[0] + ts2.arrivals[1] + fro.offsets.get(1)[0][0];
        int timeAtSourceStop = ts1.startTimes[0] + ts1.arrivals[2] + fro.offsets.get(0)[0][0];
        assertEquals(10 * 60, timeAtTargetStop - timeAtSourceStop);
    }
}