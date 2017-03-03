package com.conveyal.r5.analyst.scenario;

import com.conveyal.r5.transit.TransportNetwork;
import com.conveyal.r5.transit.TripPattern;
import com.conveyal.r5.transit.TripSchedule;

import java.util.Arrays;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Fill in the phase information on an existing frequency entry.
 * This allows us to set the phase between pure frequency routes provided in a GTFS feed.
 * This is really only intended for use in Indianapolis and will be immediately deprecated.
 */
@Deprecated
public class SetPhase extends Modification {

    /** The ID of the timetable entry on which to set the phase. */
    public String phaseId;

    /** The ID of the stop on phaseId's pattern at which to set the phase. */
    public String phaseStop;

    /** The ID of the timetable entry that phaseId will be synchronized to. */
    public String phaseFromId;

    /** The ID of the stop on phaseFromId's pattern that phaseStop will be synchronized to. */
    public String phaseFromStop;

    /** The offset between vehicle departures at phaseFromStop and phaseStop. */
    public int phaseSeconds;

    /** This is set to true when an entry is updated, just to check that only one entry exists with the given ID. */
    private transient boolean applied = false;

    @Override
    public String getType() {
        return "set-phase";
    }

    @Override
    public boolean apply(TransportNetwork network) {
        // Scan over all patterns, copying and modifying those on the given route, but leaving others untouched.
        network.transitLayer.tripPatterns = network.transitLayer.tripPatterns.stream()
                .map(this::processPattern)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        if (!applied) {
            errors.add("A set-phase modification was not applied. The frequency entry was not found: " + phaseId);
        }
        return errors.size() > 0;
    }

    @Override
    public int getSortOrder() {
        return 999;
    }

    private TripPattern processPattern (TripPattern originalPattern) {
        // Do not clone patterns that will not be changed.
        if (originalPattern.tripSchedules.stream().noneMatch(ts ->
                ts.frequencyEntryIds != null && Arrays.asList(ts.frequencyEntryIds).contains(phaseId))) {
            return originalPattern;
        }
        if (originalPattern.tripSchedules.size() != 1) {
            errors.add("In Indianapolis, all updated schedules are expected to be the only schedule on a pattern.");
        }
        TripPattern newTripPattern = originalPattern.clone();
        newTripPattern.tripSchedules = originalPattern.tripSchedules.stream().map(ts -> {
            if (ts.frequencyEntryIds == null) return ts;
            int entryIndex = Arrays.asList(ts.frequencyEntryIds).indexOf(phaseId);
            if (entryIndex == -1) return ts;
            if (ts.frequencyEntryIds.length != 1) {
                errors.add("In Indianapolis, all updated entries are expected to be the only entry on a schedule.");
            }
            // At this point, we know there are frequency entries and one of them has the specified ID.
            // Update that particular entry with the supplied phase information.
            // If IDs do not exist, errors will be produced later when phasing is actually applied.
            // Note that this only needs to work in Indianapolis, where the number of frequency entries is always 1.
            TripSchedule newTripSchedule = ts.clone();
            newTripSchedule.phaseFromId = new String[] {phaseFromId};
            newTripSchedule.phaseFromStop = new String[] {phaseFromStop};
            newTripSchedule.phaseAtStop = new String[] {phaseStop};
            newTripSchedule.phaseSeconds = new int[] {phaseSeconds};
            if (applied) {
                errors.add("A set-phase modification has been applied more than one entry in the network.");
            }
            applied = true;
            return newTripSchedule;
        }).collect(Collectors.toList());
        return newTripPattern;
    }

}
