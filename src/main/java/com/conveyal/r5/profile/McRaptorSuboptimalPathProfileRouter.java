package com.conveyal.r5.profile;

import com.conveyal.r5.transit.RouteInfo;
import com.conveyal.r5.transit.TransportNetwork;
import com.conveyal.r5.transit.TripPattern;
import com.conveyal.r5.transit.TripSchedule;
import gnu.trove.list.TIntList;
import gnu.trove.list.array.TIntArrayList;
import gnu.trove.map.TIntIntMap;
import gnu.trove.map.TIntObjectMap;
import gnu.trove.map.TLongObjectMap;
import gnu.trove.map.hash.TIntObjectHashMap;
import gnu.trove.map.hash.TLongObjectHashMap;
import org.apache.commons.math3.random.MersenneTwister;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * A profile routing implementation which uses McRAPTOR to store bags of arrival times and paths per
 * vertex, so we can find suboptimal paths. We're not using range-RAPTOR here, yet, as the obvious implementation
 * produces some very strange paths for reasons I do not fully understand.
 *
 * @author mattwigway
 */
public class McRaptorSuboptimalPathProfileRouter {
    private static final Logger LOG = LoggerFactory.getLogger(McRaptorSuboptimalPathProfileRouter.class);

    public static final int BOARD_SLACK = 60;

    /**
     * the number of searches to run (approximately). We use a constrained random walk to get about this many searches.
     */
    public static final int NUMBER_OF_SEARCHES = 35;

    private TransportNetwork network;
    private ProfileRequest request;
    private TIntIntMap accessTimes;
    private TIntIntMap egressTimes;

    private TIntObjectMap<McRaptorStateBag> bestStates = new TIntObjectHashMap<>();

    private int round = 0;

    private BitSet touchedStops = new BitSet();
    private BitSet touchedPatterns = new BitSet();

    public McRaptorSuboptimalPathProfileRouter (TransportNetwork network, ProfileRequest req, TIntIntMap accessTimes, TIntIntMap egressTimes) {
        this.network = network;
        this.request = req;
        this.accessTimes = accessTimes;
        this.egressTimes = egressTimes;
    }

    /** Get a McRAPTOR state bag for every departure minute */
    public Collection<McRaptorState> route () {
        LOG.info("Found {} access stops", accessTimes.size());
        List<McRaptorState> ret = new ArrayList<>();

        // start at end of time window and work backwards, eventually we may use range-RAPTOR
        // We use a constrained random walk to reduce the number of samples without causing an issue with variance in routes.
        // multiply by two because E[random] = 1/2 * max
        int maxSamplingFrequency = 2 * (request.toTime - request.fromTime) / NUMBER_OF_SEARCHES;

        // seed with last few digits of latitude so that requests are deterministic
        MersenneTwister mersenneTwister = new MersenneTwister((int) (request.fromLat * 1e9));

        for (int departureTime = request.toTime - 60, n = 0; departureTime > request.fromTime; departureTime -= mersenneTwister.nextInt(maxSamplingFrequency), n++) {
            //bestStates.clear(); // disabling range-raptor fttb, it's just confusing things
            touchedPatterns.clear();
            touchedStops.clear();
            round = 0;
            final int finalDepartureTime = departureTime;

            // enqueue/relax access times
            accessTimes.forEachEntry((stop, accessTime) -> {
                if (addState(stop, finalDepartureTime + accessTime, -1, -1, null))
                    touchedStops.set(stop);

                return true;
            });

            markPatterns();

            round++;

            while (doOneRound() && round < 4);

            // TODO this means we wind up with some duplicated states.
            ret.addAll(doPropagation());

            if (n % 15 == 0)
                LOG.info("minute {}, {} rounds", n, round);
        }

        return ret;
    }

    /** Perform a McRAPTOR search and extract paths */
    public Collection<PathWithTimes> getPaths () {
        Collection<McRaptorState> states = route();
        Set<PathWithTimes> paths = new HashSet<>();

        states.forEach(s -> paths.add(new PathWithTimes(s, network, request, accessTimes, egressTimes)));
        states.forEach(s -> LOG.info("{}", s.dump(network)));

        paths.forEach(p -> LOG.info("{}", p.dump(network)));

        return paths;
    }

    /** perform one round of the McRAPTOR search. Returns true if anything changed */
    private boolean doOneRound () {
        boolean ret = false;

        for (int patIdx = touchedPatterns.nextSetBit(0); patIdx >= 0; patIdx = touchedPatterns.nextSetBit(patIdx + 1)) {
            // walk along the route, picking up states as we go
            List<McRaptorState> states = new ArrayList<>();
            TIntList trips = new TIntArrayList();

            TripPattern pattern = network.transitLayer.tripPatterns.get(patIdx);

            for (int stopPositionInPattern = 0; stopPositionInPattern < pattern.stops.length; stopPositionInPattern++) {
                int stop = pattern.stops[stopPositionInPattern];

                // perform this check here so we don't needlessly loop over states at a stop that are created by getting
                // off this pattern.
                boolean stopPreviouslyReached = bestStates.containsKey(stop);

                // get off the bus, if we can
                for (int stateIndex = 0; stateIndex < states.size(); stateIndex++) {
                    int trip = trips.get(stateIndex);
                    McRaptorState state = states.get(stateIndex);
                    if (addState(stop, pattern.tripSchedules.get(trip).arrivals[stopPositionInPattern], patIdx, trip, state)) {
                        ret = true;
                        touchedStops.set(stop);
                    }
                }

                // get on the bus, if we can
                if (stopPreviouslyReached) {
                    for (McRaptorState state : bestStates.get(stop).getBestStates()) {
                        if (state.round != round - 1) continue; // don't continually reexplore states
                        if (state.pattern == patIdx || state.back != null && state.back.pattern == patIdx) continue; // don't reboard same pattern, even after a transfer

                        // find a trip, if we can
                        int currentTrip = -1; // first increment lands at zero

                        for (TripSchedule tripSchedule : pattern.tripSchedules) {
                            currentTrip++;

                            int departure = tripSchedule.departures[stopPositionInPattern];
                            if (departure > state.time + BOARD_SLACK) {
                                states.add(state);
                                trips.add(currentTrip);
                                break;
                            }
                        }
                    }
                }
            }
        }

        doTransfers();
        markPatterns();

        round++;
        return ret;
    }

    /** Perform transfers */
    private void doTransfers () {
        BitSet stopsReachedInTransitSearch = new BitSet();
        stopsReachedInTransitSearch.or(touchedStops);

        for (int stop = stopsReachedInTransitSearch.nextSetBit(0); stop >= 0; stop = stopsReachedInTransitSearch.nextSetBit(stop + 1)) {
            TIntList transfers = network.transitLayer.transfersForStop.get(stop);

            String from = network.transitLayer.stopNames.get(stop);

            for (McRaptorState state : bestStates.get(stop).getNonTransferStates()) {
                for (int transfer = 0; transfer < transfers.size(); transfer += 2) {
                    if (addState(transfers.get(transfer), state.time + transfers.get(transfer + 1), -1, -1, state)) {
                        String to = network.transitLayer.stopNames.get(transfers.get(transfer));
                        LOG.info("Transfer from {} to {} is optimal", from, to);

                        touchedStops.set(stop);
                    }
                }
            }
        }
    }

    /** propagate states to the destination */
    private Collection<McRaptorState> doPropagation () {
        McRaptorStateBag bag = new McRaptorStateBag(request.suboptimalMinutes);

        egressTimes.forEachEntry((stop, egressTime) -> {
            McRaptorStateBag bagAtStop = bestStates.get(stop);
            if (bagAtStop == null) return true;

            for (McRaptorState state : bagAtStop.getNonTransferStates()) {
                McRaptorState stateAtDest = new McRaptorState();
                stateAtDest.back = state;
                // walk to destination is transfer
                stateAtDest.pattern = -1;
                stateAtDest.trip = -1;
                stateAtDest.stop = -1;
                stateAtDest.time = state.time + egressTime;
                bag.add(stateAtDest);
            }

           return true;
        });

        // there are no non-transfer states because the walk to the destination is a transfer
        return bag.getBestStates();
    }

    /** Mark patterns at touched stops */
    private void markPatterns () {
        this.touchedPatterns.clear();

        for (int stop = touchedStops.nextSetBit(0); stop >= 0; stop = touchedStops.nextSetBit(stop + 1)) {
            network.transitLayer.patternsForStop.get(stop).forEach(pat -> {
                this.touchedPatterns.set(pat);
                return true;
            });
        }

        this.touchedStops.clear();
    }

    /** Add a state */
    private boolean addState (int stop, int time, int pattern, int trip, McRaptorState back) {
        // TODO cutoff should be based upon start time of _this_ search
        if (time > request.toTime + 3 * 60 * 60) return false; // cut off excessively long trips

        if (back != null && back.time > time)
            throw new IllegalStateException("Attempt to decrement time in state!");

        McRaptorState state = new McRaptorState();
        state.stop = stop;
        state.time = time;
        state.pattern = pattern;
        state.trip = trip;
        state.back = back;
        state.round = round;

        if (back != null) state.key = back.key;

        if (pattern != -1) state.key |= ((long) pattern) << ((round - 1) * 16);

        if (!bestStates.containsKey(stop)) bestStates.put(stop, new McRaptorStateBag(request.suboptimalMinutes));

        McRaptorStateBag bag = bestStates.get(stop);
        boolean ret = bag.add(state);

        return ret;
    }

    /**
     * This is the McRAPTOR state. It is an object, so there is a certain level of indirection, but note that all of
     * its members are primitives so the entire object can be packed.
     */
    public static class McRaptorState {
        /** what is the previous state? */
        public McRaptorState back;

        /** What is the clock time of this state (seconds since midnight) */
        public int time;

        /** On what pattern did we reach this stop (-1 indicates this is the result of a transfer) */
        public int pattern;

        /** What trip of that pattern did we arrive on */
        public int trip;

        /** the round on which this state was discovered */
        public int round;

        /** What stop are we at */
        public int stop;

        /** the key of this representing the pattern sequence. represents up to four rides, with 16 bits representing each */
        public long key = -1l << 48 | -1l << 32 | -1l << 16 | -1l;

        public String dump(TransportNetwork network) {
            StringBuilder sb = new StringBuilder();
            sb.append("BEGIN PATH DUMP (reverse chronological order, read up)\n");
            McRaptorState state = this;
            while (state != null) {
                String toStop = state.stop == -1 ? "destination" : network.transitLayer.stopNames.get(state.stop);

                if (state.pattern != -1) {
                    RouteInfo ri = network.transitLayer.routes.get(network.transitLayer.tripPatterns.get(state.pattern).routeIndex);
                    sb.append(String.format("%s %s to %s, p%st%s end at %d:%02d\n", ri.route_short_name, ri.route_long_name,
                            toStop, state.pattern, state.trip, state.time / 3600, state.time % 3600 / 60));
                }
                else {
                    sb.append(String.format("transfer via street to %s, end at %d:%02d\n", toStop, state.time / 3600, state.time % 3600 / 60));
                }

                state = state.back;
            }

            sb.append("END PATH DUMP");

            return sb.toString();
        }
    }

    /** A bag of states which maintains dominance, and also keeps transfer and non-transfer states separately. */
    public static class McRaptorStateBag {
        /** best states at stops */
        private DominatingList best;

        /** best non-transferring states at stops */
        private DominatingList nonTransfer;

        public McRaptorStateBag(int suboptimalMinutes) {
            this.best = new DominatingList(suboptimalMinutes);
            this.nonTransfer = new DominatingList(suboptimalMinutes);
        }

        public boolean add (McRaptorState state) {
            boolean ret = best.add(state);

            // state.pattern == -1 implies this is a transfer
            if (state.pattern != -1) {
                if (nonTransfer.add(state)) ret = true;
            }

            return ret;
        }

        public Collection<McRaptorState> getBestStates () {
            return best.getNonDominatedStates();
        }

        public Collection<McRaptorState> getNonTransferStates () {
            return nonTransfer.getNonDominatedStates();
        }
    }

    /** A list that handles domination automatically */
    private static class DominatingList {
        public DominatingList (int suboptimalMinutes) {
            this.suboptimalSeconds = suboptimalMinutes * 60;
        }

        /** The best time of any state in this bag */
        public int bestTime = Integer.MAX_VALUE;

        /** the number of seconds a state can be worse without being dominated. */
        public int suboptimalSeconds;

        private List<McRaptorState> list = new ArrayList<>();

        public boolean add (McRaptorState state) {
            // is this state dominated?
            if (bestTime != Integer.MAX_VALUE && bestTime + suboptimalSeconds < state.time)
                return false;

            if (state.time < bestTime) bestTime = state.time;

            // don't forget this
            list.add(state);

            return true;
        }

        /** prune dominated and excessive states */
        public void prune () {
            // group states that have the same sequence of patterns, throwing out dominated states as we go
            TLongObjectMap<McRaptorState> bestStateForPatternSequence = new TLongObjectHashMap<>();
            for (McRaptorState state : list) {
                if (state.time > bestTime + suboptimalSeconds) continue; // state is strictly dominated

                if (!bestStateForPatternSequence.containsKey(state.key) || bestStateForPatternSequence.get(state.key).time > state.time)
                    bestStateForPatternSequence.put(state.key, state);
            }

            this.list = new ArrayList<>();
            bestStateForPatternSequence.forEachValue(s -> {
                this.list.add(s);
                return true;
            });
        }

        public Collection<McRaptorState> getNonDominatedStates () {
            // We prune here. I've also tried pruning on add, but it slows the algorithm down due to all of the looping.
            // I also tried pruning once per round, but that also slows the algorithm down (perhaps because it's doing
            // so many pairwise comparisons).
            prune();
            return list;
        }
    }
}
