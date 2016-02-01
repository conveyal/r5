package com.conveyal.r5.profile;

import com.conveyal.r5.transit.TransportNetwork;
import com.conveyal.r5.transit.TripPattern;
import com.conveyal.r5.transit.TripSchedule;
import com.google.common.collect.Lists;
import gnu.trove.list.TIntList;
import gnu.trove.map.TIntIntMap;

import java.util.*;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * A path that also includes itineraries and bounds for all possible trips on the paths (even those which may not be optimal)
 */
public class PathWithTimes extends Path {
    /** Minimum travel time (seconds) */
    public int min;

    /** Average travel time (including waiting) (seconds) */
    public int avg;

    /** Maximum travel time (seconds) */
    public int max;

    public List<Itinerary> itineraries = new ArrayList<>();

    public PathWithTimes(RaptorState state, int stop, TransportNetwork network, ProfileRequest req, TIntIntMap accessTimes, TIntIntMap egressTimes) {
        super(state, stop);
        computeTimes(network, req, accessTimes, egressTimes);
    }

    private void computeTimes (TransportNetwork network, ProfileRequest req, TIntIntMap accessTimes, TIntIntMap egressTimes) {

        if (!accessTimes.containsKey(this.boardStops[0])) throw new IllegalArgumentException("Access times do not contain first stop of path!");
        if (!egressTimes.containsKey(this.alightStops[this.length - 1])) throw new IllegalArgumentException("Egress times do not contain last stop of path!");

        int accessTime = accessTimes.get(this.boardStops[0]);
        int egressTime = egressTimes.get(this.alightStops[this.length - 1]);

        if (network.transitLayer.hasFrequencies) {
            // TODO fix
            throw new UnsupportedOperationException("Frequency-based trips are not yet supported in customer-facing profile routing");
        }

        // we now know what patterns are being used, interleave to find times
        // NB no need to reverse-optimize these itineraries; we'll just filter them below.
        TripPattern[] patterns = IntStream.of(this.patterns).mapToObj(p -> network.transitLayer.tripPatterns.get(p)).toArray(s -> new TripPattern[s]);
        // find all possible times to board and alight each pattern
        // for each trip pattern, for each trip on that pattern, array of [depart origin, arrive destination]
        int[][][] times = new int[patterns.length][][];
        for (int patIdx = 0; patIdx < patterns.length; patIdx++) {
            final int pidx = patIdx;

            int fromStopInPattern = 0;
            while (patterns[patIdx].stops[fromStopInPattern] != this.boardStops[pidx]) fromStopInPattern++;
            int toStopInPattern = fromStopInPattern;
            while (patterns[patIdx].stops[toStopInPattern] != this.alightStops[pidx]) {
                // if we visit the board stop multiple times, board at the one closest to the alight stop
                // TODO better handle duplicated stops/loop routes
                if (patterns[patIdx].stops[toStopInPattern] == this.boardStops[pidx]) fromStopInPattern = toStopInPattern;
                toStopInPattern++;
            }

            final int finalFromStopInPattern = fromStopInPattern;
            final int finalToStopInPattern = toStopInPattern;

            times[patIdx] = patterns[patIdx].tripSchedules.stream()
                    .map(ts -> new int[] { ts.departures[finalFromStopInPattern], ts.arrivals[finalToStopInPattern] })
                    .toArray(s -> new int[s][]);
        }

        // sort by departure time of each trip, within each pattern
        Stream.of(times).forEach(t -> Arrays.sort(t, (t1, t2) -> t1[0] - t2[0]));

        // loop over departures within the time window
        // firstTrip is the trip on the first pattern
        int firstTrip = 0;
        while (times[0][firstTrip][0] < req.fromTime + accessTime + RaptorWorker.BOARD_SLACK) firstTrip++;

        // now interleave times
        TIMES: while (firstTrip < times[0].length) {
            Itinerary itin = new Itinerary(this.patterns.length);

            int time = times[0][firstTrip][0];

            // linear scan over timetable to do interleaving
            for (int patIdx = 0; patIdx < this.patterns.length; patIdx++) {
                int trip = 0;
                while (times[patIdx][trip][0] < time) {
                    trip++;
                    if (trip >= times[patIdx].length) break TIMES; // we've found the end of the times at which this path is possible
                }

                itin.boardTimes[patIdx] = times[patIdx][trip][0];
                itin.alightTimes[patIdx] = times[patIdx][trip][1];

                if (patIdx < this.length - 1) {
                    // find the transfer time
                    TIntList transfers = network.transitLayer.transfersForStop.get(this.alightStops[patIdx]);

                    int transferTime;

                    if (this.alightStops[patIdx] != this.boardStops[patIdx + 1]) {
                        transferTime = -1;

                        for (int i = 0; i < transfers.size(); i += 2) {
                            if (transfers.get(i) == this.boardStops[patIdx + 1]) {
                                transferTime = transfers.get(i + 1);
                                break;
                            }
                        }

                        if (transferTime == -1) {
                            throw new IllegalStateException("Did not find transfer in transit network, indicates an internal error");
                        }
                    }
                    else transferTime = 0; // no transfer time, we are at the same stop (board slack applied below)

                    // TODO should board slack be applied at the origin stop? Is this done in RaptorWorker?
                    // See also below in computeStatistics
                    time = times[patIdx][trip][1] + transferTime + RaptorWorker.BOARD_SLACK;
                }
            }

            this.itineraries.add(itin);
            firstTrip++;
        }

        sortAndFilterItineraries();
        computeStatistics(req, accessTime, egressTime);
    }

    public PathWithTimes(McRaptorSuboptimalPathProfileRouter.McRaptorState s, TransportNetwork network, ProfileRequest req, TIntIntMap accessTimes, TIntIntMap egressTimes) {
        super(s);
        computeTimes(network, req, accessTimes, egressTimes);
    }

    /**
     * filter out itineraries that are dominated by other itineraries on this path
     * (e.g. when the first vehicle runs every 10 minutes, and the second every 60, no reason to include all possible trips
     *  on the first vehicle).
     *
     * Has the same effect as reverse optimization in classic OTP.
     */
    private void sortAndFilterItineraries () {
        // reverse-sort
        this.itineraries.sort((i1, i2) -> -1 * i1.compareTo(i2));

        Itinerary prev = null;
        for (Iterator<Itinerary> it = itineraries.iterator(); it.hasNext();) {
            Itinerary current = it.next();
            // if the previously found itinerary arrives at the same time, don't save this one as it is dominated
            if (prev != null && prev.alightTimes[this.length - 1] == current.alightTimes[this.length - 1]) it.remove();
            prev = current;
        }

        // now put them in ascending order
        Collections.reverse(this.itineraries);
    }

    /**
     * Compute statistics for this particular path over the time window. This is super simple,
     * we just compute how long the path takes at every possible departure minute. There's probably an
     * elegant theoretical way to do this, but I prefer pragmatism over theory.
     */
    private void computeStatistics (ProfileRequest req, int accessTime, int egressTime) {
        int count = 0;
        int sum = 0;
        this.min = Integer.MAX_VALUE;
        this.max = 0;

        for (int start = req.fromTime; start < req.toTime; start += 60) {
            // TODO should board slack be applied at the origin stop? Is this done in RaptorWorker?
            int timeAtOriginStop = start + accessTime + RaptorWorker.BOARD_SLACK;
            int bestTimeAtDestinationStop = Integer.MAX_VALUE;

            for (Itinerary itin : this.itineraries) {
                // itinerary cannot be used at this time
                if (itin.boardTimes[0] < timeAtOriginStop) continue;

                if (itin.alightTimes[this.length - 1] < bestTimeAtDestinationStop)
                    bestTimeAtDestinationStop = itin.alightTimes[this.length - 1];
            }

            if (bestTimeAtDestinationStop == Integer.MAX_VALUE) continue; // cannot use this trip at this time

            int bestTimeAtDestination = bestTimeAtDestinationStop + egressTime;

            int travelTime = bestTimeAtDestination - start;

            count++;
            sum += travelTime;
            min = Math.min(min, travelTime);
            max = Math.max(max, travelTime);
        }

        if (count == 0) throw new IllegalStateException("No valid itineraries found for path computed in RaptorWorker");

        avg = sum / count;
    }

    /** itineraries for this path, which can be sorted by departure time */
    public static class Itinerary implements Comparable<Itinerary> {
        public Itinerary(int size) {
            this.boardTimes = new int[size];
            this.alightTimes = new int[size];
        }

        public int[] boardTimes;
        public int[] alightTimes;

        @Override
        public int compareTo(Itinerary o) {
            // TODO walk only itineraries?
            return this.boardTimes[0] - o.boardTimes[0];
        }
    }

    public String dump (TransportNetwork network) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("min %d/avg %d/max %d ", min / 60, avg / 60, max / 60));

        String[] routes = IntStream.of(patterns).mapToObj(p -> {
            int routeIdx = network.transitLayer.tripPatterns.get(p).routeIndex;
            return String.format("%s %d", network.transitLayer.routes.get(routeIdx).route_short_name, p);
        }).toArray(s -> new String[s]);

        sb.append(String.join(" -> ", routes));

        return sb.toString();
    }
}
