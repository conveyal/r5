package com.conveyal.r5.analyst.scenario;

import com.conveyal.r5.streets.StreetLayer;
import com.conveyal.r5.transit.TransitLayer;
import com.conveyal.r5.transit.TripPattern;
import com.conveyal.r5.transit.TripSchedule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Set the phase between two frequency-based patterns.
 */
public class SetTripPhasing extends Modification {
    private static final Logger LOG = LoggerFactory.getLogger(SetTripPhasing.class);

    /** The trip(s) that are having their phase set */
    public FrequencyEntrySelector target;

    /** The trip this trip is phased from */
    public FrequencyEntrySelector source;

    /** the stop in the source trip at which to enforce the phasing */
    public int sourceStopSequence;

    /** the stop in target trip at which to enforce the phasing */
    public int targetStopSequence;

    /** The phase in seconds */
    public int phaseSeconds;

    @Override
    public String getType() {
        return "set-trip-phasing";
    }

    @Override
    protected TransitLayer applyToTransitLayer(TransitLayer originalTransitLayer) {
        List<TripSchedule> targetTrips = new ArrayList<>();
        TripSchedule sourceTrip = null;
        int sourcePattern = -1;
        int sourceTripIndex = -1;
        int sourceFrequencyEntry = -1;

        TransitLayer out = originalTransitLayer.clone();
        out.tripPatterns = new ArrayList<>(originalTransitLayer.tripPatterns.size());

        int patternIndex = -1; // first increment lands at zero
        for (TripPattern originalPattern : originalTransitLayer.tripPatterns) {
            patternIndex++;

            // check if the source trip is here
            if (source.couldMatch(originalPattern)) {
                int scheduleIndex = -1; // first increment lands at zero
                for (TripSchedule schedule : originalPattern.tripSchedules) {
                    scheduleIndex++;
                    if (source.matches(schedule.tripId)) {
                        if (sourceTrip != null) {
                            throw new IllegalArgumentException(String.format("Source for phasing matched multiple trips (%s and %s, so far)", schedule.tripId, sourceTrip.tripId));
                        }

                        if (schedule.headwaySeconds == null) {
                            throw new IllegalArgumentException(String.format("Attempt to set phasing based on non-frequency trip %s", schedule.tripId));
                        }

                        // no need to worry about schedule later being cloned as we only clone target trips, and there's
                        // a check below to ensure that the source trip is not in the target trips.
                        sourceTrip = schedule;
                        sourcePattern = patternIndex;
                        sourceTripIndex = scheduleIndex;

                        // TODO don't hardwire
                        sourceFrequencyEntry = 0;

                        if (schedule.headwaySeconds.length != 1) {
                            throw new IllegalArgumentException("Multiple source frequency entries!");
                        }
                    }
                }
            }

            if (!target.couldMatch(originalPattern)) {
                out.tripPatterns.add(originalPattern); // pass-through
                continue;
            }

            TripPattern pattern = originalPattern.clone();
            // NB adding a trip also adjusts the flags for frequencies and schedules, and what days the trip pattern is
            // active. However, none of that will change on the new pattern so it's perfectly fine to just wipe the schedule
            // list.
            pattern.tripSchedules = new ArrayList<>();

            for (TripSchedule schedule : originalPattern.tripSchedules) {
                if (target.matches(schedule.tripId)) {
                    if (schedule.headwaySeconds == null) {
                        LOG.warn("Attempt to set phasing on non-headway trip, ignoring");
                    }
                    else {
                        if (schedule == sourceTrip) {
                            // circular phasing, bail
                            throw new IllegalArgumentException(String.format("Trip %s is matched by both source and target selectors when setting phasing %s", schedule.tripId, this));
                        }

                        schedule = schedule.clone();

                        targetTrips.add(schedule);
                    }
                }

                // whether it matched or not, add it the new trip pattern
                pattern.addTrip(schedule);
            }

            out.tripPatterns.add(pattern);
        }

        // now, set the phasing info on all the target trips
        if (sourceTrip == null) {
            throw new IllegalArgumentException("Trip phasing modification did not match a source frequency entry.");
        }

        int sourceStopPosition = 0;

        // will throw arrayindexoutofbounds if not found
        while (sourceTrip.stopSequences[sourceStopPosition] != sourceStopSequence) sourceStopPosition++;


        for (TripSchedule schedule : targetTrips) {
            // TODO we compute this on every iteration but only allow a single target stop sequence
            // Also note that this breaks when the target trips have the same pattern but not the same stop sequences.
            int targetStopPosition = 0;
            while (schedule.stopSequences[targetStopPosition] != targetStopSequence) targetStopPosition++;

            schedule.phasedFromPattern = new int[schedule.headwaySeconds.length];
            Arrays.fill(schedule.phasedFromPattern, sourcePattern);

            schedule.phasedFromTrip = new int[schedule.headwaySeconds.length];
            Arrays.fill(schedule.phasedFromTrip, sourceTripIndex);

            schedule.phasedFromFrequencyEntry = new int[schedule.headwaySeconds.length];
            Arrays.fill(schedule.phasedFromFrequencyEntry, sourceFrequencyEntry);

            schedule.phasedFromSourceStopPosition = new int[schedule.headwaySeconds.length];
            Arrays.fill(schedule.phasedFromSourceStopPosition, sourceStopPosition);

            schedule.phasedAtTargetStopPosition = new int[schedule.headwaySeconds.length];
            Arrays.fill(schedule.phasedAtTargetStopPosition, targetStopPosition);

            schedule.phaseSeconds = new int[schedule.headwaySeconds.length];
            Arrays.fill(schedule.phaseSeconds, phaseSeconds);
        }

        return out;
    }

    @Override
    protected StreetLayer applyToStreetLayer(StreetLayer originalStreetLayer) {
        return originalStreetLayer;
    }

    /** we use the TripPatternModification code to select the trip patterns of interest, without actually applying it as a modification */
    // TODO TripPatternSelector should maybe be superclass of TripPatternModification, however Java doesn't do multiple
    // inheritance and TripPatternModification also needs to be subclass of TransitLayerModification.
    public static class FrequencyEntrySelector extends TripScheduleModification {
        @Override
        public TripSchedule applyToTripSchedule(TripPattern tripPattern, TripSchedule tripSchedule) {
            return null;
        }

        @Override
        public String getType() {
            return "frequency-entry-selector";
        }
    }
}
