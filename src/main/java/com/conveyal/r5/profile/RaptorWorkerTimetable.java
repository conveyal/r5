package com.conveyal.r5.profile;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.util.stream.IntStream;

/**
 * A RaptorWorkerTimetable is used by a RaptorWorker to perform large numbers of RAPTOR searches very quickly
 * within a specific time window. It is used heavily in one-to-many profile routing, where we're interested in seeing
 * how access to opportunities varies over time.
 *
 * This is an alternative representation of all the TripTimes in a single Graph that are running during a particular
 * time range on a particular day. It allows for much faster spatial analysis because it places all
 * data for a single TripPattern in a contiguous region of memory, and because pre-filtering the TripTimes based on
 * whether they are running at all during the time window eliminates run-time checks that need to be performed when we
 * search for the soonest departure.
 *
 * Unlike in "normal" OTP searches, we assume non-overtaking (FIFO) vehicle behavior within a single TripPattern,
 * which is generally the case in clean input data. One key difference here is that profile routing and spatial analysis
 * do not need to take real-time (GTFS-RT) updates into account since they are intended to be generic results
 * describing a scenario in the future.
 */
public class RaptorWorkerTimetable implements Serializable {

    private static final Logger LOG = LoggerFactory.getLogger(RaptorWorkerTimetable.class);

    /* Times for schedule-based trips/patterns are stored in a 2D array. */

    int nTrips, nStops;

    /* For each trip on this pattern, an packed array of (arrival, departure) time pairs. */
    public int[][] timesPerTrip;

    /* Times for frequency-based trips are stored in parallel arrays (a column store). */

    /** Times (0-based) for frequency trips */
    int[][] frequencyTrips;

    /** Headways (seconds) for frequency trips, parallel to above. Note that frequency trips are unsorted. */
    int[] headwaySecs;

    /** Start times (seconds since noon - 12h) for frequency trips */
    int[] startTimes;

    /** End times for frequency trips */
    int[] endTimes;

    /** Indices of stops in parent data */
    public int[] stopIndices;

    /** parent raptorworkerdata of this timetable */
    public RaptorWorkerData raptorData;

    /** Mode of this pattern, see constants in com.conveyal.gtfs.model.Route */
    public int mode;

    /** Index of this pattern in RaptorData */
    public int dataIndex;

    /** for debugging, the ID of the route this represents */
    public transient String routeId;

    /** slack required when boarding a transit vehicle */
    public static final int MIN_BOARD_TIME_SECONDS = 60;

    public RaptorWorkerTimetable(int nTrips, int nStops) {
        this.nTrips = nTrips;
        this.nStops = nStops;
        timesPerTrip = new int[nTrips][];
    }

    /**
     * Return the trip index within the pattern of the soonest departure at the given stop number, requiring at least
     * MIN_BOARD_TIME_SECONDS seconds of slack. 
     */
    public int findDepartureAfter(int stop, int time) {
        for (int trip = 0; trip < timesPerTrip.length; trip++) {
            if (getDeparture(trip, stop) > time + MIN_BOARD_TIME_SECONDS) {
                return trip;
            }
        }
        return -1;
    }

    public int getArrival (int trip, int stop) {
        return timesPerTrip[trip][stop * 2];
    }

    public int getDeparture (int trip, int stop) {
        return timesPerTrip[trip][stop * 2 + 1];
    }

    public int getFrequencyDeparture (int trip, int stop, int time, int previousPattern, FrequencyRandomOffsets offsets) {
        return getFrequencyDeparture(trip, stop, time, previousPattern, offsets, null);
    }

    /**
     * Get the departure on frequency trip trip at stop stop after time time,
     * with the given boarding assumption. (Note that the boarding assumption specified may be overridden
     * by transfer rules).
     */
    public int getFrequencyDeparture (int trip, int stop, int time, int previousPattern, FrequencyRandomOffsets offsets, BoardingAssumption assumption) {
        int timeToReachStop = frequencyTrips[trip][stop * 2 + 1];

        // figure out if there is an applicable transfer rule
        TransferRule transferRule = null;

        if (previousPattern != -1) {
            // this is a transfer

            // stop index in Raptor data
            int stopIndex = stopIndices[stop];

            // first check for specific rules
            // calling containsKey can be expensive so first check if list is empty
            if (!raptorData.transferRules.isEmpty() && raptorData.transferRules.containsKey(stopIndex)) {
                for (TransferRule tr : raptorData.transferRules.get(stopIndex)) {
                    if (tr.matches(raptorData.timetablesForPattern.get(previousPattern), this)) {
                        transferRule = tr;
                        break;
                    }
                }
            }

            if (transferRule == null && !raptorData.baseTransferRules.isEmpty()) {
                // look for global rules
                // using declarative for loop because constructing a stream and doing a filter is
                // slow.
                for (TransferRule tr : raptorData.baseTransferRules) {
                    if (tr.matches(raptorData.timetablesForPattern.get(previousPattern), this)) {
                        transferRule = tr;
                        break;
                    }
                }
            }
        }

        if (assumption == null)
            assumption = raptorData.boardingAssumption;

        // if there is an applicable transfer rule, override anything that was specified elsewhere
        if (transferRule != null)
            assumption = transferRule.assumption;

        if (assumption == BoardingAssumption.RANDOM) {
            // We treat every frequency-based trip as a scheduled trip on each iteration of the Monte Carlo
            // algorithm. The reason for this is thus: consider something like the Portland Transit Mall.
            // There are many opportunities to transfer between vehicles. The transfer times between
            // Line A and Line B are not independently random at each stop but rather are correlated
            // along each line. Thus we randomize each pattern independently, not each boarding.

            // Keep in mind that there could also be correlation between patterns, especially for rail
            // vehicles that share trackage. Consider, for example, the Fredericksburg and Manassus
            // lines on the Virginia Railway Express, at Alexandria. These are two diesel heavy-rail
            // lines that share trackage. Thus the minimum transfer time between them is always at least
            // 5 minutes or so as that's as close as you can run diesel trains to each other.
            int minTime = startTimes[trip] + timeToReachStop + offsets.offsets.get(this.dataIndex)[trip];

            // move time forward to an integer multiple of headway after the minTime
            if (time < minTime)
                time = minTime;
            else
                // add enough to time to make time - minTime an integer multiple of headway
                // if the bus comes every 30 minutes and you arrive at the stop 35 minutes
                // after it first came, you have to wait 25 minutes, or headway - time already elapsed
                // time already elapsed is 35 % 30 = 5 minutes in this case.
                time += headwaySecs[trip] - (time - minTime) % headwaySecs[trip];
        }

        else {
            // move time forward if the frequency has not yet started.
            // we do this before we add the wait time, because this is the earliest possible
            // time a vehicle could reach the stop. The assumption is that the vehicle leaves
            // the terminal between zero and headway_seconds seconds after the start_time of the
            // frequency
            if (timeToReachStop + startTimes[trip] > time)
                time = timeToReachStop + startTimes[trip];

            switch (assumption) {
            case BEST_CASE:
                // do nothing
                break;
            case WORST_CASE:
                time += headwaySecs[trip];
                break;
            case HALF_HEADWAY:
                time += headwaySecs[trip] / 2;
                break;
            case FIXED:
                if (transferRule == null) throw new IllegalArgumentException("Cannot use boarding assumption FIXED without a transfer rule");
                time += transferRule.transferTimeSeconds;
                break;
            case PROPORTION:
                if (transferRule == null) throw new IllegalArgumentException("Cannot use boarding assumption PROPORTION without a transfer rule");
                time += (int) (transferRule.waitProportion * headwaySecs[trip]);
                break;
            }
        }

        if (time > timeToReachStop + endTimes[trip])
            return -1;
        else
            return time;
    }

    /**
     * Get the travel time (departure to arrival) on frequency trip trip, from stop from to stop to.  
     */
    public int getFrequencyTravelTime (int trip, int from, int to) {
        return frequencyTrips[trip][to * 2] - frequencyTrips[trip][from * 2 + 1];
    }

    /**
     * Get the number of frequency trips on this pattern (i.e. the number of trips in trips.txt, not the number of trips by vehicles)
     */
    public int getFrequencyTripCount () {
        return headwaySecs.length;
    }

    /** Does this timetable have any frequency trips? */
    public boolean hasFrequencyTrips () {
        return this.headwaySecs != null && this.headwaySecs.length > 0;
    }

    /** does this timetable have any scheduled trips? */
    public boolean hasScheduledTrips () {
        return this.timesPerTrip != null && this.timesPerTrip.length > 0;
    }

    /** The assumptions made when boarding a frequency vehicle: best case (no wait), worst case (full headway) and half headway (in some sense the average). */
    public static enum BoardingAssumption {
        BEST_CASE, WORST_CASE, HALF_HEADWAY, FIXED, PROPORTION, RANDOM;

        public static final long serialVersionUID = 1;
    }


    /**
     * A transfer rule allows specification, on a per-stop basis, of a different assumption for transfer
     * boarding times than that used in the graph-wide search.
     */
    public static class TransferRule {
        private static final long serialVersionUID = 1L;

        /** The boarding assumption to use for matched transfers */
        public RaptorWorkerTimetable.BoardingAssumption assumption;

        /** From GTFS modes; note constants in Route */
        public int[] fromMode;

        /** To GTFS modes */
        public int[] toMode;

        /** Stop label; if null will be applied to all stops. */
        public String stop;

        /**
         * The transfer time in seconds; only applied if assumption == FIXED.
         * If specified and assumption == FIXED, this transfer will always take this amount of time,
         * so this should be the time difference between vehicles. No provision is made for additional
         * slack for walking, etc., so be sure to allow enough time to be reasonable for transferring
         * at this stop.
         */
        public Integer transferTimeSeconds;

        /**
         * The proportion of the headway to apply when transferring, when assumption = PROPORTION.
         *
         * So, for example, if you set this to 0.33, and the buses run at thirty-minute frequency,
         * the wait time will be ten minutes.
         *
         * There is theoretical justification for setting this below 0.5 when the frequency-based network
         * under consideration will eventually have a schedule, even if transfers have not been and will
         * not be explicitly synchronized. Suppose all possible transfer times as a
         * proportion of headway are drawn from a distribution centered on 0.5. Now consider that there are
         * likely several competing options for each trip (how many depends on how well-connected the
         * network is). The trip planner will pick the best one, and the best one is likely to have a
         * transfer time of less than half headway (because transfer time is correlated with total trip
         * time, the objective function). Thus the average transfer time in optimal trips is less than
         * half headway.
         *
         * The same is not true of the initial wait, assuming the user gets to their the same way each time
         * they go there (which is probably true of most people who do not write transportation analytics
         * software for a living). If you leave your origin at random and do the same thing every day,
         * you will experience, on average, half headway waits. However, the schedule is fixed (the fact
         * that you're hitting a slightly different part of it each day notwithstanding), so it is reasonable
         * to optimize the choice of which sequence of routes to take, just not which to take on a given day.
         */
        public Double waitProportion;

        public String getType() {
            return "transfer-rule";
        }

        public boolean matches (RaptorWorkerTimetable from, RaptorWorkerTimetable to) {
            if (fromMode != null && !IntStream.of(fromMode).anyMatch(m -> m == from.mode))
                return false;

            if (toMode != null && !IntStream.of(toMode).anyMatch(m -> m == to.mode))
                return false;

            return true;
        }
    }

}
