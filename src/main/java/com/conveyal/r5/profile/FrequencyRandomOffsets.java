package com.conveyal.r5.profile;

import com.conveyal.r5.transit.TransitLayer;
import com.conveyal.r5.transit.TripPattern;
import com.conveyal.r5.transit.TripSchedule;
import gnu.trove.iterator.TIntObjectIterator;
import gnu.trove.map.TIntObjectMap;
import gnu.trove.map.hash.TIntObjectHashMap;
import org.apache.commons.math3.random.MersenneTwister;

/**
 * Stores random offsets for frequency trips.
 * This is not in RaptorWorkerData as RaptorWorkerData may be shared between threads.
 */
public class FrequencyRandomOffsets {
    /** map from trip pattern index to a list of offsets for trip i and frequency entry j on that pattern */
    public final TIntObjectMap<int[][]> offsets = new TIntObjectHashMap<>();
    public final TransitLayer data;

    /** The mersenne twister is a higher quality random number generator than the one included with Java */
    private MersenneTwister mt = new MersenneTwister();

    public FrequencyRandomOffsets(TransitLayer data) {
        this.data = data;

        if (!data.hasFrequencies)
            return;

        for (int pattIdx = 0; pattIdx < data.tripPatterns.size(); pattIdx++) {
            TripPattern tp = data.tripPatterns.get(pattIdx);

            if (!tp.hasFrequencies) continue;

            int[][] offsetsThisPattern = new int[tp.tripSchedules.size()][];

            for (int tripIdx = 0; tripIdx < tp.tripSchedules.size(); tripIdx++) {
                TripSchedule ts = tp.tripSchedules.get(tripIdx);
                offsetsThisPattern[tripIdx] = ts.headwaySeconds == null ? null : new int[ts.headwaySeconds.length];
            }

            offsets.put(pattIdx, offsetsThisPattern);
        }
    }

    public void randomize () {
        for (TIntObjectIterator<int[][]> it = offsets.iterator(); it.hasNext();) {
            it.advance();

            TripPattern tp = data.tripPatterns.get(it.key());
            int[][] val = it.value();

            for (int tripScheduleIndex = 0; tripScheduleIndex < val.length; tripScheduleIndex++) {
                TripSchedule schedule = tp.tripSchedules.get(tripScheduleIndex);

                // it is possible to have both frequency and non-frequency trips on the same pattern. If this is a scehduled
                // trip, don't set offset
                if (schedule.headwaySeconds == null)
                    val[tripScheduleIndex] = null;
                else {
                    for (int tripIndex = 0; tripIndex < val[tripScheduleIndex].length; tripIndex++) {
                        val[tripScheduleIndex][tripIndex] = mt.nextInt(schedule.headwaySeconds[tripIndex]);
                    }
                }
            }
        }
    }
}
