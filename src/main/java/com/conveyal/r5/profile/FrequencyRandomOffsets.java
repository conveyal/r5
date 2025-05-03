package com.conveyal.r5.profile;

import com.conveyal.analysis.models.AnalysisRequest;
import com.conveyal.r5.transit.TransitLayer;
import com.conveyal.r5.transit.TripPattern;
import com.conveyal.r5.transit.TripSchedule;
import gnu.trove.iterator.TIntObjectIterator;
import gnu.trove.map.TIntObjectMap;
import gnu.trove.map.hash.TIntObjectHashMap;
import org.apache.commons.math3.random.MersenneTwister;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static com.google.common.base.Preconditions.checkState;

/**
  * Generates and stores departure time offsets for every frequency-based set of trips.
  * This holds only one set of offsets at a time. It is re-randomized before each Monte Carlo iteration.
  * Therefore we have no memory of exactly which offsets were used in a particular Monte Carlo search.
  * It may be preferable to work with reproducible low-discrepancy sets instead of simple random samples, in which case
  * we'd need to make alternate implementations that pre-generate the entire set or use deterministic seeded generators.
  */
public class FrequencyRandomOffsets {

    /**
     * Map from trip pattern index (which is the same between filtered and unfiltered patterns) to a list of offsets
     * (in seconds) for each frequency entry on each trip of that pattern. Final dimension null for non-frequency trips.
     * In other words, patternIndex -> offsetsSeconds[tripOnPattern][frequencyEntryInTrip].
     */
    private final TIntObjectMap<int[][]> offsets = new TIntObjectHashMap<>();

    private final TransitLayer data;

    /**
     * Secondary copy of the offsets keyed on TripSchedule objects.
     * This allows lookups where patterns and trips have been filtered and int indexes no longer match unfiltered ones.
     * This can't simply replace the other offsets map because we need to fetch stop sequences from TripPatterns
     * to look up phase targets on the fly. It's awkward to store them if they're looked up in advance because the
     * natural key is frequency entries, which are not objects but just array slots.
     */
    private final Map<TripSchedule, int[]> offsetsForTripSchedule = new HashMap<>();

    /** The mersenne twister is a higher quality random number generator than the one included with Java */
    private MersenneTwister mt;

    public FrequencyRandomOffsets(TransitLayer data, ProfileRequest request) {
        this.data = data;
        if (!data.hasFrequencies) {
            return;
        }
        if (request == null || !request.lockSchedules) {
            this.mt = new MersenneTwister();
        } else {
            // Use a fixed seed for each origin
            this.mt = new MersenneTwister((int) (request.fromLat * 1e9));
        }
        // Create skeleton empty data structure with slots for all offsets that will be generated.
        for (int pattIdx = 0; pattIdx < data.tripPatterns.size(); pattIdx++) {
            TripPattern tp = data.tripPatterns.get(pattIdx);
            if (!tp.hasFrequencies) {
                continue;
            }
            int[][] offsetsThisPattern = new int[tp.tripSchedules.size()][];

            for (int tripIdx = 0; tripIdx < tp.tripSchedules.size(); tripIdx++) {
                TripSchedule ts = tp.tripSchedules.get(tripIdx);
                offsetsThisPattern[tripIdx] = ts.headwaySeconds == null ? null : new int[ts.headwaySeconds.length];
            }

            offsets.put(pattIdx, offsetsThisPattern);
        }
    }

    /**
     * Return the random offset ("phase") in seconds generated for the given frequency entry of the given TripSchedule.
     * Lookup is now by TripSchedule object as trips are filtered, losing track of their int indexes in unfiltered lists.
     */
    public int getOffsetSeconds (TripSchedule tripSchedule, int freqEntryIndex) {
        int[] offsetsPerEntry = offsetsForTripSchedule.get(tripSchedule);
        checkState(
            tripSchedule.nFrequencyEntries() == offsetsPerEntry.length,
            "Offsets array length should exactly match number of freq entries in TripSchedule."
        );
        int offset = offsetsPerEntry[freqEntryIndex];
        checkState(offset >= 0, "Frequency entry offset was not randomized.");
        return offset;
    }

     /**
      * Take a new Monte Carlo draw if requested (i.e. if boarding assumption is not half-headway): for each
      * frequency-based route, choose how long after service starts the first vehicle leaves (the route's "phase").
      * We run all Raptor rounds with one draw before proceeding to the next draw.
      */
    public void randomize () {
        // The number of TripSchedules for which we still need to generate a random offset.
        int remaining = 0;

        // First, initialize all offsets for all trips and entries on this pattern with -1 ("not yet randomized").
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

        // If some randomized schedules are synchronized with other schedules ("phased") we perform multiple passes. In
        // each pass we randomize only schedules whose phasing target is already known (randomized in a previous pass).
        // This will loop forever if the phasing dependency graph has cycles - we must catch stalled progress. This is
        // essentially performing depth-first traversal of the dependency graph iteratively without materializing it.
        while (remaining > 0) {
            int remainingAfterPreviousPass = remaining;

            for (TIntObjectIterator<int[][]> it = offsets.iterator(); it.hasNext(); ) {
                it.advance();
                // The only thing we need from the TripPattern is the stop sequence, which is used only in phase solving.
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
                                // This trip is not phased so does not require solving. Generate a random offset
                                // immediately. Do this only once - don't overwrite with a new random number on each
                                // phase solving pass, as other trips may be be phased from this one.
                                if (val[tripScheduleIndex][frequencyEntryIndex] == -1) {
                                    val[tripScheduleIndex][frequencyEntryIndex] = mt.nextInt(schedule.headwaySeconds[frequencyEntryIndex]);
                                    remaining--;
                                }
                            }
                            else {
                                // This trip is phased from another.
                                if (val[tripScheduleIndex][frequencyEntryIndex] != -1) {
                                    continue; // Offset has already have been generated.
                                }
                                // No random offset has been generated for this trip yet.
                                // Find source phase information. TODO refactor to use references instead of ints.
                                int[] source = data.frequencyEntryIndexForId.get(schedule.phaseFromId[frequencyEntryIndex]);
                                // Throw a meaningful error when invalid IDs are encountered instead of NPE.
                                // Really this should be done when resolving or applying the modifications rather than during search.
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
                                    // TODO find all stop IDs in advance when resolving/applying modifications or constructing FrequencyRandomOffsets.
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
            if (remainingAfterPreviousPass == remaining && remaining > 0) {
                throw new IllegalArgumentException("Cannot solve phasing, you may have a circular reference!");
            }
            // Copy results of randomization to a Map keyed on TripSchedules (instead of TripPattern index ints). This
            // allows looking up offsets in a context where we only have a filtered list of running frequency trips and
            // don't know the original unfiltered index of the trip within the pattern. Ideally we'd just build the
            // original map keyed on TripSchedules (or hypothetical FreqEntries) but that reqires a lot of refactoring.
            offsetsForTripSchedule.clear();
            for (TIntObjectIterator<int[][]> it = offsets.iterator(); it.hasNext(); ) {
                it.advance();
                TripPattern tripPattern = data.tripPatterns.get(it.key());
                int[][] offsetsForTrip = it.value();
                for (int ts = 0; ts < tripPattern.tripSchedules.size(); ts++) {
                    TripSchedule tripSchedule = tripPattern.tripSchedules.get(ts);
                    // On patterns with mixed scheduled and frequency trips, scheduled trip slots will be null.
                    // Maps can store null values, but there's no point in storing them. We only store non-null arrays.
                    if (offsetsForTrip[ts] != null) {
                        offsetsForTripSchedule.put(tripSchedule, offsetsForTrip[ts]);
                    }
                }
            }
        }
    }
}
