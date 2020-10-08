// in transit package so we can set package-private variables in TripPatternKey
package com.conveyal.r5.transit;

import com.conveyal.gtfs.model.Frequency;
import com.conveyal.gtfs.model.Trip;
import com.conveyal.r5.profile.FrequencyRandomOffsets;
import gnu.trove.list.array.TIntArrayList;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertTrue;

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

        for (int i = 0; i < 9; i++) {
            layer.stopIdForIndex.add(String.format("FEED:STOP_%d", i));
        }

        // create two frequency-based lines

        TripPattern pattern1 = new TripPattern(new TIntArrayList(new int[] { 1, 2, 3, 4 }));
        Trip t1 = new Trip();
        t1.feed_id = "FEED";
        t1.trip_id = "TRIP1";
        Frequency f1 = new Frequency();
        f1.start_time = 5 * 60 * 60;
        f1.end_time = 11 * 60 * 60;
        f1.headway_secs = 30 * 60;
        f1.exact_times = 0;
        f1.trip_id = "TRIP1";
        List<Frequency> frequencies = Arrays.asList(f1);
        TripSchedule ts1 = TripSchedule.create(t1, new int [] { 0, 120, 240, 360 }, new int [] { 0, 120, 240, 360 }, frequencies, new int[] { 1, 2, 3, 4 }, 0);
        pattern1.addTrip(ts1);

        layer.tripPatterns.add(pattern1);

        TripPattern pattern2 = new TripPattern(new TIntArrayList(new int[] { 5, 6, 7, 8 }));
        Trip t2 = new Trip();
        t2.feed_id = "FEED";
        t2.trip_id = "TRIP2";
        Frequency f2 = new Frequency();
        // use a different start time to ensure that that is taken into account
        f2.start_time = 5 * 60 * 60 + 8 * 60;
        f2.end_time = 11 * 60 * 60;
        f2.headway_secs = 30 * 60;
        f2.exact_times = 0;
        f2.trip_id = "TRIP2";
        frequencies = Arrays.asList(f2);
        TripSchedule ts2 = TripSchedule.create(t2, new int [] { 0, 60, 90, 180 }, new int [] { 0, 60, 90, 180 }, frequencies, new int[] { 5, 6, 7, 8 }, 0);

        ts2.phaseFromId = new String[] { "FEED:TRIP1_05:00:00_to_11:00:00_every_30m00s" };
        ts2.phaseAtStop = new String[] { "FEED:STOP_6" };
        ts2.phaseFromStop = new String[] { "FEED:STOP_3" };
        ts2.phaseSeconds = new int[] { 600 };

        pattern2.addTrip(ts2);

        layer.tripPatterns.add(pattern2);
        layer.rebuildTransientIndexes();

        FrequencyRandomOffsets fro = new FrequencyRandomOffsets(layer);
        fro.randomize();

        // check that phasing is correct
        // offset indices are trip pattern, trip, frequency entry
        int timeAtTargetStop = ts2.startTimes[0] + ts2.departures[1] + fro.offsets.get(1)[0][0];
        int timeAtSourceStop = ts1.startTimes[0] + ts1.departures[2] + fro.offsets.get(0)[0][0];
        int timeDifference = timeAtTargetStop - timeAtSourceStop;
        // Depending on how large the offset on the first route is, the new route may come 10 minutes after on its first
        // trip, or 20 minutes before (which is the same phasing, just changing which route arrives first).
        assertTrue(10 * 60 == timeDifference || -1 * (30 - 10) * 60 == timeDifference);
    }

    /** Test that phasing using arrivals not departures at last stop. */
    @Test
    public void testPhasingAtLastStop () {
        // make a fake transit layer
        TransitLayer layer = new TransitLayer();
        layer.hasFrequencies = true;
        layer.hasSchedules = false;

        for (int i = 0; i < 9; i++) {
            layer.stopIdForIndex.add(String.format("FEED:STOP_%d", i));
        }

        // create two frequency-based lines

        TripPattern pattern1 = new TripPattern(new TIntArrayList(new int[] { 1, 2, 3, 4 }));
        Trip t1 = new Trip();
        t1.feed_id = "FEED";
        t1.trip_id = "TRIP1";
        Frequency f1 = new Frequency();
        f1.start_time = 5 * 60 * 60;
        f1.end_time = 11 * 60 * 60;
        f1.headway_secs = 30 * 60;
        f1.exact_times = 0;
        f1.trip_id = "TRIP1";
        List<Frequency> frequencies = Arrays.asList(f1);
        TripSchedule ts1 = TripSchedule.create(t1, new int [] { 0, 60, 90, 180 }, new int [] { 0, 60, 90, 4800 }, frequencies, new int[] { 1, 2, 3, 4 }, 0);
        pattern1.addTrip(ts1);

        layer.tripPatterns.add(pattern1);

        TripPattern pattern2 = new TripPattern(new TIntArrayList(new int[] { 5, 6, 7, 8 }));
        Trip t2 = new Trip();
        t2.feed_id = "FEED";
        t2.trip_id = "TRIP2";
        Frequency f2 = new Frequency();
        // use a different start time to ensure that that is taken into account
        f2.start_time = 5 * 60 * 60 + 8 * 60;
        f2.end_time = 11 * 60 * 60;
        f2.headway_secs = 30 * 60;
        f2.exact_times = 0;
        f2.trip_id = "TRIP2";
        frequencies = Arrays.asList(f2);
        TripSchedule ts2 = TripSchedule.create(t2, new int [] { 0, 60, 90, 180 }, new int [] { 0, 60, 90, 4810 }, frequencies, new int[] { 5, 6, 7, 8 }, 0);

        ts2.phaseFromId = new String[] { "FEED:TRIP1_05:00:00_to_11:00:00_every_30m00s" };
        ts2.phaseAtStop = new String[] { "FEED:STOP_8" };
        ts2.phaseFromStop = new String[] { "FEED:STOP_4" };
        ts2.phaseSeconds = new int[] { 600 };

        pattern2.addTrip(ts2);

        layer.tripPatterns.add(pattern2);
        layer.rebuildTransientIndexes();

        FrequencyRandomOffsets fro = new FrequencyRandomOffsets(layer);
        fro.randomize();

        // check that phasing is correct
        // offset indices are trip pattern, trip, frequency entry
        int timeAtTargetStop = ts2.startTimes[0] + ts2.arrivals[3] + fro.offsets.get(1)[0][0];
        int timeAtSourceStop = ts1.startTimes[0] + ts1.arrivals[3] + fro.offsets.get(0)[0][0];
        int timeDifference = timeAtTargetStop - timeAtSourceStop;
        // Depending on how large the offset on the first route is, the new route may come 10 minutes after on its first
        // trip, or 20 minutes before (which is the same phasing, just changing which route arrives first).
        assertTrue(10 * 60 == timeDifference || -1 * (30 - 10) * 60 == timeDifference);
    }
}