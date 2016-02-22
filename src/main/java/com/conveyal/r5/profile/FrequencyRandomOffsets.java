package com.conveyal.r5.profile;

import com.conveyal.r5.transit.TransitLayer;
import com.conveyal.r5.transit.TripPattern;
import com.conveyal.r5.transit.TripSchedule;
import gnu.trove.iterator.TIntObjectIterator;
import gnu.trove.map.TIntObjectMap;
import gnu.trove.map.hash.TIntObjectHashMap;
import org.apache.commons.math3.random.MersenneTwister;

import java.util.Arrays;

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
        int remaining = 0;

        // first clear old offsets
        for (TIntObjectIterator<int[][]> it = offsets.iterator(); it.hasNext(); ) {
            it.advance();
            for (int[] offsetsPerEntry : it.value()) {
                Arrays.fill(offsetsPerEntry, -1);
                remaining += offsetsPerEntry.length;
            }
        }

        while (remaining > 0) {
            int remainingAfterPreviousRound = remaining;

            for (TIntObjectIterator<int[][]> it = offsets.iterator(); it.hasNext(); ) {
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
                            if (schedule.phasedFromPattern == null || schedule.phasedFromPattern[tripIndex] == -1) {
                                // not phased. also, don't overwrite on each iteration, as other trips may be phased from this one
                                if (val[tripScheduleIndex][tripIndex] == -1) {
                                    val[tripScheduleIndex][tripIndex] = mt.nextInt(schedule.headwaySeconds[tripIndex]);
                                    remaining--;
                                }
                            }
                            else {
                                if (val[tripScheduleIndex][tripIndex] != -1) continue; // already randomized

                                // check if it's already been randomized
                                int previousOffset = offsets.get(schedule.phasedFromPattern[tripIndex])[schedule.phasedFromTrip[tripIndex]][schedule.phasedFromFrequencyEntry[tripIndex]];

                                if (previousOffset != -1) {
                                    TripPattern phaseFromPattern = data.tripPatterns.get(schedule.phasedFromPattern[tripIndex]);
                                    TripSchedule phaseFromSchedule = phaseFromPattern.tripSchedules.get(schedule.phasedFromTrip[tripIndex]);

                                    // figure out the offset if they were to pass the stops at the same time
                                    int timeAtSourceStop = phaseFromSchedule.startTimes[tripIndex] +
                                            phaseFromSchedule.arrivals[schedule.phasedFromSourceStopPosition[tripIndex]] +
                                            previousOffset;

                                    // figure out when the target trip passes the stop if the offset were 0.
                                    int timeAtTargetStop = schedule.startTimes[tripIndex] +
                                            schedule.arrivals[schedule.phasedAtTargetStopPosition[tripIndex]];

                                    int offset = timeAtSourceStop - timeAtTargetStop;

                                    // this is the offset so the trips arrive at the same time. We now add the desired phase.
                                    offset += schedule.phaseSeconds[tripIndex];

                                    // make sure it's positive
                                    while (offset < 0) offset += schedule.headwaySeconds[tripIndex];

                                    // make it as small as possible...
                                    offset %= schedule.headwaySeconds[tripIndex];

                                    val[tripScheduleIndex][tripIndex] = offset;
                                    remaining--;
                                }
                            }
                        }
                    }
                }
            }

            if (remainingAfterPreviousRound == remaining && remaining > 0) {
                throw new IllegalArgumentException("Cannot solve phasing, you may have a circular reference!");
            }
        }
    }
}
