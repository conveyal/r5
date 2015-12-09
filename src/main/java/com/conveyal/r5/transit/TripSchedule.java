package com.conveyal.r5.transit;

import com.conveyal.gtfs.model.Frequency;
import com.conveyal.gtfs.model.Trip;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.util.List;
import java.util.stream.Collectors;

/**
 *
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

    public int flags;
    public int serviceCode;
    public TripSchedule nextInBlock = null;

    /** static factory so we can return null */
    public static TripSchedule create (Trip trip, int[] arrivals, int[] departures, int serviceCode) {
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

        if (trip.frequencies != null && !trip.frequencies.isEmpty()) {
            if (trip.frequencies.stream().allMatch(f -> f.end_time < f.start_time)) {
                LOG.error("All frequency entries on trip {} have end time before start time, excluding this trip.", trip.trip_id);
                return null;
            }
        }

        return new TripSchedule(trip, arrivals, departures, serviceCode);
    }

    // Maybe make a TripSchedule.Factory so we don't have to pass in serviceCode or map.
    private TripSchedule(Trip trip, int[] arrivals, int[] departures, int serviceCode) {
        this.tripId = trip.trip_id;
        if (trip.bikes_allowed > 0) {
            setFlag(TripFlag.BICYCLE);
        }
        if (trip.wheelchair_accessible > 0) {
            setFlag(TripFlag.WHEELCHAIR);
        }
        this.arrivals = arrivals;
        this.departures = departures;
        this.serviceCode = serviceCode;

        // TODO handle exact times!

        if (trip.frequencies != null && !trip.frequencies.isEmpty()) {

            // filter to valid frequencies
            // TODO some trips may have no service after this filter is applied
            List<Frequency> frequencies = trip.frequencies.stream()
                    .filter(f -> {
                        if (f.start_time > f.end_time) {
                            LOG.warn("Frequency entry for trip {} has end time before start time; it will not be used. Perhaps this is an issue with overnight service?", trip.trip_id);
                            return false;
                        }

                        return true;
                    })
                    .collect(Collectors.toList());

            if (!frequencies.isEmpty()) {
                // this is a frequency-based trip
                this.headwaySeconds = new int[frequencies.size()];
                this.startTimes = new int[frequencies.size()];
                this.endTimes = new int[frequencies.size()];

                // reset everything to zero-based on frequency-based trips
                if (arrivals.length > 0) {
                    int firstArrival = arrivals[0];

                    for (int i = 0; i < arrivals.length; i++) {
                        arrivals[i] -= firstArrival;
                    }

                    for (int i = 0; i < departures.length; i++) {
                        departures[i] -= firstArrival;
                    }
                }

                // TODO: should we sort frequency entries?

                int fidx = 0;

                for (Frequency f : frequencies) {
                    if (f.exact_times == 1) {
                        LOG.warn("Exact times frequency trips not supported, treating as inexact!");
                    }

                    this.headwaySeconds[fidx] = f.headway_secs;
                    this.endTimes[fidx] = f.end_time;
                    this.startTimes[fidx] = f.start_time;
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

    /** @return whether it makes sense for the supplied trip to be served by the same vehicle as this trip. */
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
     * @return whether any part of this occurs during the given time range (expressed in seconds after midnight).
     * TODO frequencies
     */
    public boolean overlapsTimeRange (int fromTime, int toTime) {
        int firstStopTime = departures[0];
        int lastStopTime = arrivals[arrivals.length - 1];
        return firstStopTime <= toTime && lastStopTime >= fromTime;
    }

}
