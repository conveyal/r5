package com.conveyal.r5.profile;

import com.conveyal.r5.api.util.Stats;

import java.util.Collection;
import java.util.stream.IntStream;

/**
 * This is used in Modeify. "Stats" summarize the travel time for a set of similar paths, giving the minimum,
 * maximum, and average travel and wait over all those paths, over a whole departure time window.
 */
public class StatsCalculator {
    /**
     * Compute statistics for this particular path over the time window. This is super simple,
     * we just compute how long the path takes at every possible departure minute. There's probably an
     * elegant theoretical way to do this, but I prefer pragmatism over theory.
     */
    public static StatsCollection computeStatistics (ProfileRequest req, int accessTime, int egressTime,
                                          int nSegments, Collection<PathWithTimes.Itinerary> itineraries) {
        Stats stats = new Stats();
        Stats[] rideStats = IntStream.range(0, nSegments).mapToObj(i -> new Stats()).toArray(Stats[]::new);
        Stats[] waitStats = IntStream.range(0, nSegments).mapToObj(i -> new Stats()).toArray(Stats[]::new);

        for (int start = req.fromTime; start < req.toTime; start += 60) {
            // TODO should board slack be applied at the origin stop? Is this done in RaptorWorker?
            int timeAtOriginStop = start + accessTime + FastRaptorWorker.BOARD_SLACK_SECONDS;
            int bestTimeAtDestinationStop = Integer.MAX_VALUE;

            PathWithTimes.Itinerary bestItinerary = null;
            for (PathWithTimes.Itinerary itin : itineraries) {
                // itinerary cannot be used at this time
                if (itin.boardTimes[0] < timeAtOriginStop) continue;

                if (itin.alightTimes[nSegments - 1] < bestTimeAtDestinationStop) {
                    bestTimeAtDestinationStop = itin.alightTimes[nSegments - 1];
                    bestItinerary = itin;
                }
            }

            if (bestItinerary == null) continue; // cannot use this trip at this time

            int bestTimeAtDestination = bestTimeAtDestinationStop + egressTime;

            int travelTime = bestTimeAtDestination - start;

            stats.num++;
            stats.avg += travelTime;
            stats.min = Math.min(stats.min, travelTime);
            stats.max = Math.max(stats.max, travelTime);

            // accumulate stats for each leg
            for (int leg = 0; leg < nSegments; leg++) {
                Stats ride = rideStats[leg];
                int rideLen = bestItinerary.alightTimes[leg] - bestItinerary.boardTimes[leg];
                ride.num++;
                ride.avg += rideLen;
                ride.min = Math.min(ride.min, rideLen);
                ride.max = Math.max(ride.max, rideLen);

                Stats wait = waitStats[leg];

                int arriveAtStopTime = leg == 0 ? timeAtOriginStop : bestItinerary.arriveAtBoardStopTimes[leg];

                int waitTime = bestItinerary.boardTimes[leg] - arriveAtStopTime;

                wait.num++;
                wait.avg += waitTime;
                wait.min = Math.min(waitTime, wait.min);
                wait.max = Math.max(waitTime, wait.max);
            }
        }

        if (stats.num == 0) throw new IllegalStateException("No valid itineraries found for path computed in RaptorWorker");

        stats.avg /= stats.num;

        for (Stats[] statSet : new Stats[][] { rideStats, waitStats }) {
            for (Stats s : statSet) {
                s.avg /= s.num;
            }
        }

        return new StatsCollection(stats, waitStats, rideStats);
    }

    public static class StatsCollection {
        public Stats stats;
        public Stats[] waitStats;
        public Stats[] rideStats;

        public StatsCollection (Stats stats, Stats[] waitStats, Stats[] rideStats) {
            this.stats = stats;
            this.rideStats = rideStats;
            this.waitStats = waitStats;
        }
    }
}
