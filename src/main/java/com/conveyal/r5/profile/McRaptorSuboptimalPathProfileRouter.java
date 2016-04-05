package com.conveyal.r5.profile;

import com.conveyal.r5.transit.RouteInfo;
import com.conveyal.r5.transit.TransportNetwork;
import com.conveyal.r5.transit.TripPattern;
import com.conveyal.r5.transit.TripSchedule;
import gnu.trove.list.TIntList;
import gnu.trove.map.TIntIntMap;
import gnu.trove.map.TIntObjectMap;
import gnu.trove.map.TObjectIntMap;
import gnu.trove.map.hash.TIntObjectHashMap;
import gnu.trove.map.hash.TObjectIntHashMap;
import gnu.trove.set.TIntSet;
import gnu.trove.set.hash.TIntHashSet;
import org.apache.commons.math3.random.MersenneTwister;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

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

    /** maximum number of rounds (rides) */
    public static final int MAX_ROUNDS = 4;

    public static final int[] EMPTY_INT_ARRAY = new int[0];

    /** large primes for use in hashing, computed using R numbers package */
    public static final int[] PRIMES = new int[] { 400000009, 200000033, 2, 1100000009, 1900000043, 800000011, 1300000003,
            1000000007, 500000003, 300000007, 1700000009, 100000007, 700000031, 900000011, 1800000011, 1400000023,
            600000007, 1600000009, 1200000041, 1500000041 };

    // DEBUG: USED TO EVALUATE HASH PERFORMANCE
//    Set<StatePatternKey> keys = new HashSet<>(); // all unique keys
//    TIntSet hashes = new TIntHashSet(); // all unique hashes

    /**
     * the number of searches to run (approximately). We use a constrained random walk to get about this many searches.
     */
    public static final int NUMBER_OF_SEARCHES = 20;

    private TransportNetwork network;
    private ProfileRequest request;
    private TIntIntMap accessTimes;
    private TIntIntMap egressTimes;

    private TIntObjectMap<McRaptorStateBag> bestStates = new TIntObjectHashMap<>();

    /** target pruning as described in the RAPTOR paper: cut off states that can't possibly reach the target in time */
    private int bestTimeAtTarget = Integer.MAX_VALUE;

    private int round = 0;
    // used in hashing
    //private int roundSquared = 0;

    private BitSet touchedStops;
    private BitSet touchedPatterns;
    private BitSet patternsNearDestination;
    private BitSet servicesActive;

    public McRaptorSuboptimalPathProfileRouter (TransportNetwork network, ProfileRequest req, TIntIntMap accessTimes, TIntIntMap egressTimes) {
        this.network = network;
        this.request = req;
        this.accessTimes = accessTimes;
        this.egressTimes = egressTimes;
        this.touchedStops = new BitSet(network.transitLayer.getStopCount());
        this.touchedPatterns = new BitSet(network.transitLayer.tripPatterns.size());
        this.patternsNearDestination = new BitSet(network.transitLayer.tripPatterns.size());
        this.servicesActive = network.transitLayer.getActiveServicesForDate(req.date);
    }

    /** Get a McRAPTOR state bag for every departure minute */
    public Collection<McRaptorState> route () {
        LOG.info("Found {} access stops:\n{}", accessTimes.size(), dumpStops(accessTimes));
        LOG.info("Found {} egress stops:\n{}", egressTimes.size(), dumpStops(egressTimes));

        long startTime = System.currentTimeMillis();

        // find patterns near destination
        // on the final round of the search we only explore these patterns
        this.egressTimes.forEachKey(s -> {
            network.transitLayer.patternsForStop.get(s).forEach(p -> {
                patternsNearDestination.set(p);
                return true;
            });
            return true;
        });

        LOG.info("{} patterns found near the destination", patternsNearDestination.cardinality());

        List<McRaptorState> ret = new ArrayList<>();

        // start at end of time window and work backwards, eventually we may use range-RAPTOR
        // We use a constrained random walk to reduce the number of samples without causing an issue with variance in routes.
        // multiply by two because E[random] = 1/2 * max
        int maxSamplingFrequency = 2 * (request.toTime - request.fromTime) / NUMBER_OF_SEARCHES;

        // seed with last few digits of latitude so that requests are deterministic
        MersenneTwister mersenneTwister = new MersenneTwister((int) (request.fromLat * 1e9));

        for (int departureTime = request.toTime - 60, n = 0; departureTime > request.fromTime; departureTime -= mersenneTwister.nextInt(maxSamplingFrequency), n++) {
            bestStates.clear(); // for range-raptor to be valid in a search with a limited number of transfers we need a separate state after each round
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

            // NB the walk search is an initial round, so MAX_ROUNDS + 1
            while (doOneRound() && round < MAX_ROUNDS + 1);

            // TODO this means we wind up with some duplicated states.
            ret.addAll(doPropagation());

            if (n % 15 == 0)
                LOG.info("minute {}, {} rounds", n, round);
        }

        // DEBUG: print hash table performance
//        LOG.info("Hash performance: {} hashes, {} states", hashes.size(), keys.size());

        LOG.info("McRAPTOR took {}ms", System.currentTimeMillis() - startTime);

        return ret;
    }

    /** dump out all stop names */
    public String dumpStops (TIntIntMap stops) {
        StringBuilder sb = new StringBuilder();

        stops.forEachEntry((stop, time) -> {
            String stopName = network.transitLayer.stopNames.get(stop);
            sb.append(String.format("%s (%d) at %sm %ss\n", stopName, stop, time / 60, time % 60));
            return true;
        });

        return sb.toString();
    }

    /** Perform a McRAPTOR search and extract paths */
    public Collection<PathWithTimes> getPaths () {
        Collection<McRaptorState> states = route();
        // using a map here because even paths that are considered equal may have different times and we want to apply
        // strict dominance to equal paths
        Map<PathWithTimes, PathWithTimes> paths = new HashMap<>();

        states.forEach(s -> {
            PathWithTimes pwt = new PathWithTimes(s, network, request, accessTimes, egressTimes);

            if (!paths.containsKey(pwt) || paths.get(pwt).avg > pwt.avg)
                paths.put(pwt, pwt);
        });
        //states.forEach(s -> LOG.info("{}", s.dump(network)));

        paths.values().forEach(p -> LOG.info("{}", p.dump(network)));

        return new ArrayList<>(paths.values());
    }

    /** perform one round of the McRAPTOR search. Returns true if anything changed */
    private boolean doOneRound () {
        // optimization: on the last round, only explore patterns near the destination
        if (round == MAX_ROUNDS)
            touchedPatterns.and(patternsNearDestination);

        for (int patIdx = touchedPatterns.nextSetBit(0); patIdx >= 0; patIdx = touchedPatterns.nextSetBit(patIdx + 1)) {
            // walk along the route, picking up states as we go
            // We never propagate more than one state from the same previous pattern _sequence_
            // e.g. don't have two different ways to do L2 -> Red -> Green, one with a transfer at Van Ness
            // and one with a transfer at Cleveland Park.
            // However, do allow L1 -> Red -> Green and L2 -> Red -> Green to exist simultaneously. (if we were only
            // looking at the previous pattern, these would be identical when we board the green line because they both
            // came from the red line).
            Map<StatePatternKey, McRaptorState> statesPerPatternSequence = new HashMap<>();
            TObjectIntMap<StatePatternKey> tripsPerPatternSequence = new TObjectIntHashMap<>();

            TripPattern pattern = network.transitLayer.tripPatterns.get(patIdx);
            //skips trip patterns with trips which don't run on wanted date
            if (!pattern.servicesActive.intersects(servicesActive)) {
                continue;
            }

            for (int stopPositionInPattern = 0; stopPositionInPattern < pattern.stops.length; stopPositionInPattern++) {
                int stop = pattern.stops[stopPositionInPattern];

                // perform this check here so we don't needlessly loop over states at a stop that are all created by
                // getting off this pattern.
                boolean stopPreviouslyReached = bestStates.containsKey(stop);

                // get off the bus, if we can
                for (Map.Entry<StatePatternKey, McRaptorState> e : statesPerPatternSequence.entrySet()) {
                    int trip = tripsPerPatternSequence.get(e.getKey());
                    TripSchedule sched = pattern.tripSchedules.get(trip);
                    //Skips trips which don't run on wanted date
                    if(!servicesActive.get(sched.serviceCode)) {
                        continue;
                    }

                    if (addState(stop, sched.arrivals[stopPositionInPattern], patIdx, trip, e.getValue()))
                        touchedStops.set(stop);
                }

                // get on the bus, if we can
                if (stopPreviouslyReached) {
                    STATES: for (McRaptorState state : bestStates.get(stop).getBestStates()) {
                        if (state.round != round - 1) continue; // don't continually reexplore states

                        int prevPattern = state.pattern;

                        // this state is a transfer, get the pattern used to reach the transfer
                        // if pattern is -1 and state.back is null, then this is the initial walk to reach transit
                        if (prevPattern == -1 && state.back != null) prevPattern = state.back.pattern;

                        // don't reexplore trips.
                        // NB checking and preventing reboarding any pattern that's previously been boarded doesn't save
                        // a signifiant amount of search time (anecdotally), and forbids some rare but possible optimal routes
                        // that use the same pattern twice (consider a trip from Shady Grove to Glenmont in DC that cuts
                        // through Maryland on a bus before reboarding the Glenmont-bound red line).
                        if (prevPattern == patIdx) continue;

                        // find a trip, if we can
                        int currentTrip = -1; // first increment lands at zero

                        for (TripSchedule tripSchedule : pattern.tripSchedules) {
                            currentTrip++;
                            //Skips trips which don't run on wanted date
                            if(!servicesActive.get(tripSchedule.serviceCode)) {
                                continue;
                            }

                            int departure = tripSchedule.departures[stopPositionInPattern];
                            if (departure > state.time + BOARD_SLACK) {

                                StatePatternKey spk = new StatePatternKey(state);

                                if (!statesPerPatternSequence.containsKey(spk) || tripsPerPatternSequence.get(spk) > currentTrip) {
                                    statesPerPatternSequence.put(spk, state);
                                    tripsPerPatternSequence.put(spk, currentTrip);
                                }

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
        return !touchedPatterns.isEmpty();
    }

    /** Perform transfers */
    private void doTransfers () {
        BitSet stopsTouchedByTransfer = new BitSet(network.transitLayer.getStopCount());

        for (int stop = touchedStops.nextSetBit(0); stop >= 0; stop = touchedStops.nextSetBit(stop + 1)) {
            TIntList transfers = network.transitLayer.transfersForStop.get(stop);

            for (McRaptorState state : bestStates.get(stop).getNonTransferStates()) {
                for (int transfer = 0; transfer < transfers.size(); transfer += 2) {
                    int toStop = transfers.get(transfer);
                    if (addState(toStop, state.time + transfers.get(transfer + 1), -1, -1, state)) {
                        String to = network.transitLayer.stopNames.get(transfers.get(transfer));
                        //LOG.info("Transfer from {} to {} is optimal", from, to);

                        stopsTouchedByTransfer.set(toStop);
                    }
                }
            }
        }

        // copy all stops touched by transfers into the touched stops bitset.
        touchedStops.or(stopsTouchedByTransfer);
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
        /**
         * local pruning, and cutting off of excessively long searches
         * NB need to have cutoff be relative to toTime because otherwise when we do range-RAPTOR we'll have left over states
         * that are past the cutoff.
         */
        // NB subtracting suboptimal minutes from LHS to avoid int overflow when adding to Integer.MAX_VALUE
        if (time - request.suboptimalMinutes * 60 > bestTimeAtTarget || time > request.toTime + 3 * 60 * 60) return false;

        if (back != null && back.time > time)
            throw new IllegalStateException("Attempt to decrement time in state!");

        McRaptorState state = new McRaptorState();
        state.stop = stop;
        state.time = time;
        state.pattern = pattern;
        state.trip = trip;
        state.back = back;
        state.round = round;

        if (pattern != -1) {
            if (state.back != null) {
                state.patterns = Arrays.copyOf(state.back.patterns, round);
                state.patternHash = state.back.patternHash;
            }
            else {
                state.patterns = new int[1];
            }

            state.patterns[round - 1] = pattern;

            // NB using the below implementation from Arrays.hashCode makes the algorithm 2x slower
            // (not using Arrays.hashCode, obviously that would be slow due to retraversal, but just using the same algorithm is slow)
            //state.patternHash = state.patternHash * 31 + pattern;
            // Take advantage of the fact that we only ever compare states from the same round, and maximize entropy at each round
            // also keep in mind that
            state.patternHash += pattern * PRIMES[round];
        }
        else if (state.back != null) {
            state.patterns = state.back.patterns;
            state.patternHash = state.back.patternHash;
        }

        // BELOW CODE IS USED TO EVALUATE HASH PERFORMANCE
        // uncomment it, the variables it uses, and the log statement in route to print a hash collision report
//        keys.add(new StatePatternKey(state));
//        hashes.add(state.patternHash);

        if (!bestStates.containsKey(stop)) bestStates.put(stop, new McRaptorStateBag(request.suboptimalMinutes));

        McRaptorStateBag bag = bestStates.get(stop);
        boolean optimal = bag.add(state);

        // target pruning: keep track of best time at destination
        if (optimal && pattern != -1 && egressTimes.containsKey(stop)) {
            int timeAtDest = time + egressTimes.get(stop);
            if (timeAtDest < bestTimeAtTarget) bestTimeAtTarget = timeAtDest;
        }

        return optimal;
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

        /** the patterns used in this state */
        public int[] patterns = EMPTY_INT_ARRAY;

        public int patternHash;

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

        private List<McRaptorState> list = new LinkedList<>();

        public boolean add (McRaptorState state) {
            // is this state dominated?
            if (bestTime != Integer.MAX_VALUE && bestTime + suboptimalSeconds < state.time)
                return false;

            // apply strict dominance if there is a state at the previous round on the same previous pattern arriving at this
            // stop (prevents reboarding/hopping between routes on common trunks)
            // For example, consider the red line in DC, which runs from Shady Grove to Glenmont. At rush hour, every other
            // train is a short-turn pattern running from Grosvenor to Silver Spring. While these are clearly separate
            // patterns, there's never a reason to get off the Silver Spring train and get on the Glenmont train, unless
            // you want to go past Silver Spring. These trains are running every two minutes, so you can jump between them
            // a few times before the suboptimal dominance will cut off the search.

            // We don't just want to cut off switching to another vehicle on the same route direction; consider the Rush+
            // yellow line in DC. One pattern runs from Fort Totten to Huntington, while the other runs from Greenbelt
            // to Franconia-Springfield. Suppose you wanted to go from Greenbelt to Huntington. It would make complete sense
            // to transfer from one yellow line train to another.

            // We also don't want to cut off switching between vehicles on common trunks. Consider the L2 and the Red Line
            // in DC, which both serve Connecticut Ave between Van Ness-UDC and Farragut Square. The Red Line is much faster
            // so it makes sense to transfer to it if you get on the L2 somewhere outside the common trunk.

            // In this particular case, the L2 and the red line don't serve the same stops; however, this is still important.
            // Suppose you wanted to get on the circulator, which meets the red line and L2 at Woodley Park. It could make
            // sense to take L2 -> Red -> Circulator, even though the L2 would have taken you all the way. That's why we
            // apply strict dominance, rather than just forbidding this situation.

            // We only look back one pattern; the reason for this is that we want to avoid a lot of looping in a function
            // that gets called a lot, and it seems unlikely that there would be time to take two other patterns and still
            // slip into the window of suboptimality. I haven't tested it though to see its effect on response times.
            if (state.pattern != -1 && state.patterns.length > 1) {
                for (McRaptorState s : list) {
                    if (s.round == state.round - 1 && s.pattern == state.patterns[s.round - 1] && s.time <= state.time) {
                        return false;
                    }
                }
            }

            if (state.time < bestTime) bestTime = state.time;

            // don't forget this
            list.add(state);

            return true;
        }

        /** prune dominated and excessive states */
        public void prune () {
            // group states that have the same sequence of patterns, throwing out dominated states as we go
            for (Iterator<McRaptorState> it = list.iterator(); it.hasNext();) {
                if (it.next().time >= bestTime + suboptimalSeconds)
                    it.remove();
            }
        }

        public Collection<McRaptorState> getNonDominatedStates () {
            // We prune here. I've also tried pruning on add, but it slows the algorithm down due to all of the looping.
            // I also tried pruning once per round, but that also slows the algorithm down (perhaps because it's doing
            // so many pairwise comparisons).
            prune();
            return list;
        }
    }

    private static class StatePatternKey {
        McRaptorState state;

        public StatePatternKey (McRaptorState state) {
            this.state = state;
        }

        public int hashCode () {
            return state.patternHash;
        }

        public boolean equals (Object o) {
            if (o instanceof StatePatternKey) {
                return Arrays.equals(state.patterns, ((StatePatternKey) o).state.patterns);
            }

            return false;
        }
    }
}
