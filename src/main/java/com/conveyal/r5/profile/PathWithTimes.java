package com.conveyal.r5.profile;

import com.conveyal.r5.api.util.LegMode;
import com.conveyal.r5.api.util.Stats;
import com.conveyal.r5.transit.TransportNetwork;
import com.conveyal.r5.transit.TripPattern;
import gnu.trove.list.TIntList;
import gnu.trove.map.TIntIntMap;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * A path that also includes itineraries and bounds for all possible trips on the paths (even those which may not be optimal)
 */
public class PathWithTimes extends Path {
    /** Stats for the entire path */
    public Stats stats;

    /** Wait stats for each leg */
    public Stats[] waitStats;

    /** Ride stats for each leg */
    public Stats[] rideStats;

    public LegMode accessMode;

    public LegMode egressMode;

    public List<Itinerary> itineraries = new ArrayList<>();

    public PathWithTimes(RaptorState state, int stop, TransportNetwork network, ProfileRequest req, TIntIntMap accessTimes, TIntIntMap egressTimes) {
        super(state, stop);
        computeTimes(network, req, accessTimes, egressTimes);
    }

    public PathWithTimes(McRaptorSuboptimalPathProfileRouter.McRaptorState s, TransportNetwork network, ProfileRequest req, TIntIntMap accessTimes, TIntIntMap egressTimes) {
        super(s);
        this.accessMode = s.accessMode;
        this.egressMode = s.egressMode;
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
        while (times[0][firstTrip][0] < req.fromTime + accessTime + FastRaptorWorker.BOARD_SLACK_SECONDS) firstTrip++;

        // now interleave times
        double walkSpeedMillimetersPerSecond = req.walkSpeed * 1000;
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
                                int transferDistanceMillimeters = transfers.get(i + 1);
                                transferTime = (int)(transferDistanceMillimeters / walkSpeedMillimetersPerSecond);
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
                    time = times[patIdx][trip][1] + transferTime + FastRaptorWorker.BOARD_SLACK_SECONDS;
                    itin.arriveAtBoardStopTimes[patIdx + 1] = time;
                }
            }

            this.itineraries.add(itin);
            firstTrip++;
        }

        sortAndFilterItineraries();
        computeStatistics(req, accessTime, egressTime);
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
    public void computeStatistics (ProfileRequest req, int accessTime, int egressTime) {
        StatsCalculator.StatsCollection coll =
                StatsCalculator.computeStatistics(req, accessTime, egressTime, length, itineraries);
        this.stats = coll.stats;
        this.waitStats = coll.waitStats;
        this.rideStats = coll.rideStats;
    }

    /** itineraries for this path, which can be sorted by departure time */
    public static class Itinerary implements Comparable<Itinerary> {
        public Itinerary(int size) {
            this.boardTimes = new int[size];
            this.alightTimes = new int[size];
            this.arriveAtBoardStopTimes = new int[size];
        }

        public int[] boardTimes;
        public int[] alightTimes;

        /** The time you arrive at the board stop before each pattern, left 0 on the first leg as it's undefined */
        public int[] arriveAtBoardStopTimes;

        @Override
        public int compareTo(Itinerary o) {
            // TODO walk only itineraries?
            return this.boardTimes[0] - o.boardTimes[0];
        }
    }

    // FIXME we are using a map with unorthodox definitions of hashcode and equals to make them serve as map keys.
    // We should instead wrap PathWithTimes or copy the relevant fields into a PatternSequenceKey class.

    public int hashCode () {
        return super.hashCode() ^ (accessMode.hashCode() * 31 + egressMode.hashCode() * 29);
    }

    public boolean equals (Object o) {
        if (o instanceof PathWithTimes) {
            PathWithTimes p = (PathWithTimes) o;
            return super.equals(p) && accessMode == p.accessMode && egressMode == p.egressMode;
        }

        return false;
    }

    public String dump (TransportNetwork network) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("min %d/avg %d/max %d %s ", stats.min / 60, stats.avg / 60, stats.max / 60, accessMode.toString()));

        String[] routes = IntStream.of(patterns).mapToObj(p -> {
            int routeIdx = network.transitLayer.tripPatterns.get(p).routeIndex;
            return String.format("%s %d", network.transitLayer.routes.get(routeIdx).route_short_name, p);
        }).toArray(s -> new String[s]);

        sb.append(String.join(" -> ", routes));

        sb.append(' ');
        sb.append(egressMode.toString());

        return sb.toString();
    }
}
