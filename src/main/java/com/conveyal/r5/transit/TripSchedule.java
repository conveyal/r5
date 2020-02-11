package com.conveyal.r5.transit;

import com.conveyal.gtfs.model.Frequency;
import com.conveyal.gtfs.model.Trip;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.util.Collection;
import java.util.stream.Collectors;

/**
 * This represents the arrival and departure times of a single GTFS trip within a TripPattern.
 * If this is a frequency trip, it also records the different headways throughout the day, and when those headways
 * begin and end.
 */
public class TripSchedule implements Serializable, Comparable<TripSchedule>, Cloneable {

    private static final Logger LOG = LoggerFactory.getLogger(TripSchedule.class);

    public String tripId;

    public int[] arrivals;
    public int[] departures;

    /** if null, this trip is not frequency based */
    public int[] headwaySeconds = null;

    /** start times for frequency entries */
    public int[] startTimes = null;

    /** end times for frequency entries */
    public int[] endTimes = null;

    /** IDs for frequency entries */
    public String[] frequencyEntryIds;

    public int flags;
    public int serviceCode;
    public TripSchedule nextInBlock = null;

    // Phasing information: how does this frequency-based trip schedule interact with others?

    /**
     * Frequency entry each frequency entry on this trip is phased from. If the array is null, no entries are phased;
     * if any entry is null, that entry is not phased.
     */
    public String[] phaseFromId;

    /**
     * Scoped stop ID on this trip where this the phase is applied.
     */
    public String[] phaseAtStop;

    /** Scoped stop ID on source (phased-from) trip where the phase is applied */
    public String[] phaseFromStop;

    /**
     * Phase between trips. Applied to departure time except at last stop, where it is applied to arrival time.
     *
     * If the from and to trips have the same frequency, this is pretty straightforward. If they do not, it is slightly
     * more complicated, and the guarantee of phasing is weaker. We will make it so that one of the trips on this frequency
     * entry passes phaseSeconds after one of the trips on the other entry. We will also ensure that the headway of this
     * frequency entry is a factor or constant multiple of the headway of the other.
     *
     * TODO this works in the abstract, however what happens when the time windows of frequency entries are small and do
     * not start at the same time? I think there's a possibility that no trip would "fall into place" during the active
     * period of the frequency entry.
     */
    public int[] phaseSeconds;

    /**
     * The stop sequences of this trip in the GTFS. Unfortunately we need to store this at the trip level as every
     * trip on a pattern could have different stop sequence numbers. We need to save the sequence numbers so we can
     * uniquely identify stop positions in patterns in SetPhasing modifications (this could potentially be ripped out
     * when we change how setting phasing is done, for example by not allowing changing phasing on existing routes at all)
     *
     * TODO phasing now works differently, is this still needed?
     */
    public int[] stopSequences;

    /**
     * Static factory method, allowing us to return null for invalid inputs (unlike the constructor).
     * This is used when building a TransitLayer from GTFS, or when applying an AddTrips modification.
     *
     * @param frequencies "pure" frequency entries - the exact_times field is currently ignored, and exact_times
     *                    frequency expansion must be done before creating the TripSchedule.
     */
    public static TripSchedule create (Trip trip, int[] arrivals, int[] departures, Collection<Frequency> frequencies, int[] stopSequences, int serviceCode) {
        // ensure that trip times are monotonically increasing, otherwise throw them out
        for (int i = 0; i < arrivals.length; i++) {
            if (departures[i] < arrivals[i]) {
                LOG.error("Trip {} departs stop before it arrives, excluding this trip.", trip.trip_id);
                return null;
            }
            if (i > 0 && arrivals[i] < departures[i - 1]) {
                LOG.error("Trip {} arrives at a stop before departing the previous stop, excluding this trip.", trip.trip_id);
                return null;
            }
        }
        if (frequencies != null && !frequencies.isEmpty()) {
            if (frequencies.stream().allMatch(f -> f.end_time < f.start_time)) {
                LOG.error("All frequency entries on trip {} have end time before start time, excluding this trip.", trip.trip_id);
                return null;
            }
        }
        return new TripSchedule(trip, arrivals, departures, frequencies, stopSequences, serviceCode);
    }

    /**
     * TODO handle exact_times frequency entries from GTFS
     * @param frequencies "pure" frequency entries - the exact_times field is currently ignored, and exact_times
     *                    frequency expansion must be done before creating the TripSchedule.
     */
    private TripSchedule(Trip trip, int[] arrivals, int[] departures, Collection<Frequency> frequencies, int[] stopSequences, int serviceCode) {
        String scopedTripId = String.join(":", trip.feed_id, trip.trip_id);
        this.tripId = scopedTripId;
        if (trip.bikes_allowed == 1) {
            setFlag(TripFlag.BICYCLE);
        }
        if (trip.wheelchair_accessible == 1) {
            setFlag(TripFlag.WHEELCHAIR);
        }
        this.arrivals = arrivals;
        this.departures = departures;
        this.stopSequences = stopSequences;
        this.serviceCode = serviceCode;

        if (frequencies != null && !frequencies.isEmpty()) {

            // filter to valid frequencies
            // TODO some trips may have no service after this filter is applied
            frequencies = frequencies.stream().filter(f -> {
                boolean valid = f.start_time <= f.end_time;
                if (!valid) {
                    LOG.warn("Frequency entry for trip {} has end time before start time; it will not be used. " +
                            "Perhaps this is an issue with overnight service?", trip.trip_id);
                }
                return valid;
            }).collect(Collectors.toList());

            if (!frequencies.isEmpty()) {
                // this is a frequency-based trip
                this.headwaySeconds = new int[frequencies.size()];
                this.startTimes = new int[frequencies.size()];
                this.endTimes = new int[frequencies.size()];
                this.frequencyEntryIds = new String[frequencies.size()];
                // Shift all arrival and departure times to be zero-based on frequency-based trips
                if (arrivals.length > 0) {
                    int firstArrival = arrivals[0];
                    for (int i = 0; i < arrivals.length; i++) {
                        arrivals[i] -= firstArrival;
                    }
                    for (int i = 0; i < departures.length; i++) {
                        departures[i] -= firstArrival;
                    }
                }
                // TODO: Verify whether is is necessary or advantageous to sort frequency entries.
                int fidx = 0;
                for (Frequency f : frequencies) {
                    if (f.exact_times == 1) {
                        LOG.warn("GTFS frequency entries with exact_times are not supported, treating as inexact!");
                    }
                    this.headwaySeconds[fidx] = f.headway_secs;
                    this.endTimes[fidx] = f.end_time;
                    this.startTimes[fidx] = f.start_time;
                    // feed scope frequency IDs
                    this.frequencyEntryIds[fidx] = String.join(":", trip.feed_id, f.getId());
                    fidx++;
                }
            }
        }
    }

    public void setFlag (TripFlag tripFlag) {
        flags |= tripFlag.flag;
    }

    public boolean getFlag (TripFlag tripFlag) {
        return (flags & tripFlag.flag) != 0;
    }

    @Override
    public int compareTo(TripSchedule other) {
        return this.departures[0] - other.departures[0];
    }

    @Override
    public TripSchedule clone() {
        try {
            return (TripSchedule) super.clone();
        } catch (CloneNotSupportedException e) {
            throw new RuntimeException(e);
        }
    }

    /** return whether it makes sense for the supplied trip to be served by the same vehicle as this trip. */
    public void chainTo (TripSchedule that) {
        // Check that chain is temporally coherent
        if (arrivals[arrivals.length - 1] <= that.departures[0]) {
            // FIXME need to resolve ambiguity around service dates + block IDs.
            // nextInBlock = that;
        } else {
            // FIXME this error is extremely common in Portland because block IDs are recycled across service days.
            LOG.debug("Trip {} arrives at terminus after the next trip in its block departs.", tripId);
        }


    }

    /**
     * I can't think of any reason we need to use the first arrivals and last departure,
     * rather than the first departure and last arrival, given that we don't currently support interlining.
     * Once we support interlining, this will matter, as you might arrive at the first stop on an interline just inside the time window.
     *
     * @return whether any part of this occurs during the given time range (expressed in seconds after midnight).
     * TODO -- frequencies --
     */
    public boolean overlapsTimeRange (int fromTime, int toTime) {
        int firstStopTime, lastStopTime;

        if (this.headwaySeconds != null) {
            firstStopTime = Integer.MAX_VALUE;
            lastStopTime = Integer.MIN_VALUE;

            if (this.headwaySeconds.length == 0) {
                LOG.warn("Frequency trip has no frequency entries!");
                return false;
            }

            for (int i = 0; i < this.headwaySeconds.length; i++) {
                firstStopTime = Math.min(firstStopTime, this.startTimes[i]);
                // NB the last stop time on a frequency entry is the end time plus the travel time, as start/end times
                // represent the behavior at the first stop.
                lastStopTime = Math.max(lastStopTime, this.endTimes[i] + this.arrivals[this.arrivals.length - 1]);
            }
        }
        else {
            firstStopTime = departures[0];
            lastStopTime = arrivals[arrivals.length - 1];
        }

        return firstStopTime <= toTime && lastStopTime >= fromTime;
    }

    public int getNStops() {
        return arrivals.length;
    }

    public int nFrequencyEntries() {
        return headwaySeconds.length;
    }

}
