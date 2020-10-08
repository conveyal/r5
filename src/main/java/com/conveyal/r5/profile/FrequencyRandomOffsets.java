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
  * Generates and stores departure time offsets for every frequency-based set of trips.
  * This holds only one set of offsets at a time. It is re-randomized before each Monte Carlo iteration.
  * Therefore we have no memory of exactly which offsets were used in a particular Monte Carlo search.
  * It may be preferable to work with reproducible low-discrepancy sets instead of simple random samples, in which case
  * we'd need to make alternate implementations that pre-generate the entire set or use deterministic seeded generators.
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

     /**
      * Take a new Monte Carlo draw if requested (i.e. if boarding assumption is not half-headway): for each
      * frequency-based route, choose how long after service starts the first vehicle leaves (the route's "phase").
      * We run all Raptor rounds with one draw before proceeding to the next draw.
      */
    public void randomize () {
        int remaining = 0;

        // First, initialize all offsets for all trips and entries on this pattern with -1s
        for (TIntObjectIterator<int[][]> it = offsets.iterator(); it.hasNext(); ) {
            it.advance();
            for (int[] offsetsPerEntry : it.value()) {
                // If this pattern has mixed schedule and frequency trips, and this is a scheduled trip,
                // it doesn't need to be randomized and there is no offsets array in this position.
                if (offsetsPerEntry == null) continue;
                Arrays.fill(offsetsPerEntry, -1);
                remaining += offsetsPerEntry.length;
            }
        }

        while (remaining > 0) {
            int remainingAfterPreviousRound = remaining;

            for (TIntObjectIterator<int[][]> it = offsets.iterator(); it.hasNext(); ) {
                it.advance();

                TripPattern pattern = data.tripPatterns.get(it.key());
                int[][] val = it.value();

                for (int tripScheduleIndex = 0; tripScheduleIndex < val.length; tripScheduleIndex++) {
                    TripSchedule schedule = pattern.tripSchedules.get(tripScheduleIndex);

                    // It is possible to have both frequency and non-frequency (scheduled) trips on the same pattern.
                    // If this is a scheduled trip, don't set offset.
                    if (schedule.headwaySeconds == null) {
                        val[tripScheduleIndex] = null;
                    } else {
                        for (int frequencyEntryIndex = 0; frequencyEntryIndex < val[tripScheduleIndex].length; frequencyEntryIndex++) {
                            if (schedule.phaseFromId == null || schedule.phaseFromId[frequencyEntryIndex] == null) {
                                // not phased. also, don't overwrite with new random number on each iteration, as other
                                // trips may be phased from this one
                                if (val[tripScheduleIndex][frequencyEntryIndex] == -1) {
                                    val[tripScheduleIndex][frequencyEntryIndex] = mt.nextInt(schedule.headwaySeconds[frequencyEntryIndex]);
                                    remaining--;
                                }
                            }
                            else {
                                if (val[tripScheduleIndex][frequencyEntryIndex] != -1) continue; // already randomized

                                // find source phase information
                                int[] source = data.frequencyEntryIndexForId.get(schedule.phaseFromId[frequencyEntryIndex]);
                                // Throw a meaningful error when invalid IDs are encountered instead of NPE.
                                // Really this should be done when applying the modifications rather than during the search.
                                if (source == null) {
                                    throw new RuntimeException("This pattern ID specified in a scenario does not exist: "
                                            + schedule.phaseFromId[frequencyEntryIndex]);
                                }
                                int sourcePatternIdx = source[0];
                                int sourceTripScheduleIdx = source[1];
                                int sourceFrequencyEntryIdx = source[2];

                                // check if it's already been randomized
                                int previousOffset = offsets.get(sourcePatternIdx)[sourceTripScheduleIdx][sourceFrequencyEntryIdx];

                                if (previousOffset != -1) {
                                    TripPattern phaseFromPattern = data.tripPatterns.get(sourcePatternIdx);
                                    TripSchedule phaseFromSchedule = phaseFromPattern.tripSchedules.get(sourceTripScheduleIdx);

                                    // figure out stop indices
                                    int sourceStopIndexInPattern = 0;
                                    int sourceStopIndexInNetwork = data.indexForStopId.get(schedule.phaseFromStop[frequencyEntryIndex]);

                                    // TODO check that stop IDs were found.

                                    while (sourceStopIndexInPattern < phaseFromPattern.stops.length &&
                                            phaseFromPattern.stops[sourceStopIndexInPattern] != sourceStopIndexInNetwork) {
                                        sourceStopIndexInPattern++;
                                    }

                                    if (sourceStopIndexInPattern == phaseFromPattern.stops.length) {
                                        throw new IllegalArgumentException(String.format("Stop %s was not found in source pattern!", schedule.phaseFromStop[frequencyEntryIndex]));
                                    }

                                    int targetStopIndexInPattern = 0;
                                    int targetStopIndexInNetwork = data.indexForStopId.get(schedule.phaseAtStop[frequencyEntryIndex]);

                                    while (targetStopIndexInPattern < pattern.stops.length &&
                                            pattern.stops[targetStopIndexInPattern] != targetStopIndexInNetwork) {
                                        targetStopIndexInPattern++;
                                    }

                                    // TODO This should really be checked also before modifications are applied.
                                    if (targetStopIndexInPattern == pattern.stops.length) {
                                        throw new IllegalArgumentException(String.format("Stop %s was not found in target pattern!", schedule.phaseAtStop[frequencyEntryIndex]));
                                    }

                                    // use arrivals at last stop
                                    int[] sourceTravelTimes = sourceStopIndexInPattern < phaseFromPattern.stops.length - 1 ?
                                            phaseFromSchedule.departures : phaseFromSchedule.arrivals;

                                    // figure out the offset if they were to pass the stops at the same time
                                    int timeAtSourceStop = phaseFromSchedule.startTimes[sourceFrequencyEntryIdx] +
                                            sourceTravelTimes[sourceStopIndexInPattern] +
                                            previousOffset;

                                    // use arrivals at last stop
                                    int[] targetTravelTimes = targetStopIndexInPattern < pattern.stops.length - 1 ?
                                            schedule.departures : schedule.arrivals;

                                    // figure out when the target trip passes the stop if the offset were 0.
                                    int timeAtTargetStop = schedule.startTimes[frequencyEntryIndex] +
                                            targetTravelTimes[targetStopIndexInPattern];

                                    int offset = timeAtSourceStop - timeAtTargetStop;

                                    // this is the offset so the trips arrive at the same time. We now add the desired phase.
                                    offset += schedule.phaseSeconds[frequencyEntryIndex];

                                    // make sure it's positive
                                    while (offset < 0) offset += schedule.headwaySeconds[frequencyEntryIndex];

                                    // make it as small as possible...
                                    offset %= schedule.headwaySeconds[frequencyEntryIndex];

                                    val[tripScheduleIndex][frequencyEntryIndex] = offset;
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
