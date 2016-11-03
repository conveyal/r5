package com.conveyal.r5.profile;

import com.conveyal.r5.analyst.WebMercatorGridPointSet;
import com.conveyal.r5.analyst.cluster.AnalystClusterRequest;
import com.conveyal.r5.analyst.cluster.ResultEnvelope;
import com.conveyal.r5.api.util.LegMode;
import com.conveyal.r5.api.util.TransitModes;
import com.conveyal.r5.streets.LinkedPointSet;
import com.conveyal.r5.streets.StreetRouter;
import com.conveyal.r5.transit.RouteInfo;
import com.conveyal.r5.transit.TransitLayer;
import com.conveyal.r5.transit.TransportNetwork;
import com.conveyal.r5.transit.TripFlag;
import com.conveyal.r5.transit.TripPattern;
import com.conveyal.r5.transit.TripSchedule;
import gnu.trove.list.TIntList;
import gnu.trove.map.TIntIntMap;
import gnu.trove.map.TIntObjectMap;
import gnu.trove.map.TObjectIntMap;
import gnu.trove.map.hash.TIntObjectHashMap;
import gnu.trove.map.hash.TObjectIntHashMap;
import org.apache.commons.math3.random.MersenneTwister;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.function.Supplier;

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
    public int NUMBER_OF_SEARCHES = 20;

    private final boolean DUMP_STOPS = false;

    private LinkedPointSet pointSet = null;

    /** Use a list for the iterations since we aren't sure how many there will be (we're using random sampling over the departure minutes) */
    public List<int[]> timesAtTargetsEachIteration = null;

    private TransportNetwork network;
    private ProfileRequest request;
    private AnalystClusterRequest clusterRequest;
    private Map<LegMode, TIntIntMap> accessTimes;
    private Map<LegMode, TIntIntMap> egressTimes = null;

    private FrequencyRandomOffsets offsets;

    private TIntObjectMap<McRaptorStateBag> bestStates = new TIntObjectHashMap<>();



    private int round = 0;
    // used in hashing
    //private int roundSquared = 0;

    private BitSet touchedStops;
    private BitSet touchedPatterns;
    private BitSet patternsNearDestination;
    private BitSet servicesActive;

    /** output from analyst algorithm will end up here */
    public PropagatedTimesStore propagatedTimesStore;

    /** In order to properly do target pruning we store the best times at each target _by access mode_, so car trips don't quash walk trips */
    private TObjectIntMap<LegMode> bestTimesAtTargetByAccessMode = new TObjectIntHashMap<>(4, 0.95f, Integer.MAX_VALUE);

    public McRaptorSuboptimalPathProfileRouter (TransportNetwork network, ProfileRequest req, Map<LegMode, TIntIntMap> accessTimes, Map<LegMode, TIntIntMap> egressTimes) {
        this.network = network;
        this.request = req;
        this.accessTimes = accessTimes;
        this.egressTimes = egressTimes;
        this.touchedStops = new BitSet(network.transitLayer.getStopCount());
        this.touchedPatterns = new BitSet(network.transitLayer.tripPatterns.size());
        this.patternsNearDestination = new BitSet(network.transitLayer.tripPatterns.size());
        this.servicesActive = network.transitLayer.getActiveServicesForDate(req.date);
        this.offsets = new FrequencyRandomOffsets(network.transitLayer);
    }

    public McRaptorSuboptimalPathProfileRouter (TransportNetwork network, AnalystClusterRequest req, LinkedPointSet pointSet) {
        this.network = network;
        this.request = req.profileRequest;
        this.clusterRequest = req;
        this.pointSet = pointSet;
        this.touchedStops = new BitSet(network.transitLayer.getStopCount());
        this.touchedPatterns = new BitSet(network.transitLayer.tripPatterns.size());
        this.patternsNearDestination = new BitSet(network.transitLayer.tripPatterns.size());
        this.servicesActive = network.transitLayer.getActiveServicesForDate(req.profileRequest.date);
        this.timesAtTargetsEachIteration = new ArrayList<>();
        this.offsets = new FrequencyRandomOffsets(network.transitLayer);
    }

    /** Get a McRAPTOR state bag for every departure minute */
    public Collection<McRaptorState> route () {
        // TODO hack changing original request!
        if (request.transitModes == null || request.transitModes.isEmpty() || request.transitModes.contains(TransitModes.TRANSIT)) {
            request.transitModes = EnumSet.allOf(TransitModes.class);
        }

        if (accessTimes == null) computeAccessTimes();

        long startTime = System.currentTimeMillis();

        // find patterns near destination
        // on the final round of the search we only explore these patterns
        if (this.egressTimes != null) {
            this.egressTimes.values().forEach(times -> times.forEachKey(s -> {
                network.transitLayer.patternsForStop.get(s).forEach(p -> {
                    patternsNearDestination.set(p);
                    return true;
                });
                return true;
            }));

            LOG.info("{} patterns found near the destination", patternsNearDestination.cardinality());
        }

        List<McRaptorState> ret = new ArrayList<>();

        // start at end of time window and work backwards, eventually we may use range-RAPTOR
        // We use a constrained random walk to reduce the number of samples without causing an issue with variance in routes.
        // multiply by two because E[random] = 1/2 * max
        int maxSamplingFrequency = 2 * (request.toTime - request.fromTime) / NUMBER_OF_SEARCHES;

        // This random number generator will be seeded with a combination of time and the instance's identity hash code.
        // This makes it truly random for all practical purposes. To make results repeatable from one run to the next,
        // seed with some characteristic of the request itself, e.g. (int) (request.fromLat * 1e9)
        MersenneTwister mersenneTwister = new MersenneTwister();

        for (int departureTime = request.toTime - 60, n = 0; departureTime > request.fromTime; departureTime -= mersenneTwister.nextInt(maxSamplingFrequency), n++) {

            // we're not using range-raptor so it's safe to change the schedule on each search
            offsets.randomize();

            bestStates.clear(); // if we ever use range-raptor, for it to be valid in a search with a limited number of transfers we need a separate state after each round
            touchedPatterns.clear();
            touchedStops.clear();
            round = 0;
            final int finalDepartureTime = departureTime;

            // enqueue/relax access times
            accessTimes.forEach((mode, times) -> times.forEachEntry((stop, accessTime) -> {
                if (addState(stop, -1, -1, finalDepartureTime + accessTime, -1, -1, null, mode))
                    touchedStops.set(stop);

                return true;
            }));

            markPatterns();

            round++;

            // NB the walk search is an initial round, so MAX_ROUNDS + 1
            while (doOneRound() && round < MAX_ROUNDS + 1);

            // TODO this means we wind up with some duplicated states.
            if (egressTimes != null) {
                ret.addAll(doPropagationToDestination());
            }
            else {
                doPropagationToPointSet(departureTime);
            }

            if (n % 15 == 0)
                LOG.info("minute {}, {} rounds", n, round);
        }

        // DEBUG: print hash table performance
//        LOG.info("Hash performance: {} hashes, {} states", hashes.size(), keys.size());

        // analyst request, create a propagated times store
        if (egressTimes == null) {
            propagatedTimesStore = new PropagatedTimesStore(pointSet.size());
            BitSet includeInAverages = new BitSet();
            includeInAverages.set(0, timesAtTargetsEachIteration.size());
            // TODO min/max not appropriate without explicitly calculated extrema in frequency search
            propagatedTimesStore.setFromArray(timesAtTargetsEachIteration.toArray(new int[timesAtTargetsEachIteration.size()][]), request.reachabilityThreshold);
        }

        LOG.info("McRAPTOR took {}ms", System.currentTimeMillis() - startTime);

        return ret;
    }

    /** compute access times based on the profile request. NB this does not do a search-per-mode */
    private void computeAccessTimes() {
        StreetRouter streetRouter = new StreetRouter(network.streetLayer);

        EnumSet<LegMode> modes = request.accessModes;
        LegMode mode;
        if (modes.contains(LegMode.CAR)) {
            streetRouter.streetMode = StreetMode.CAR;
            mode = LegMode.CAR;
        } else if (modes.contains(LegMode.BICYCLE)) {
            streetRouter.streetMode = StreetMode.BICYCLE;
            mode = LegMode.BICYCLE;
        } else {
            streetRouter.streetMode = StreetMode.WALK;
            mode = LegMode.WALK;
        }

        streetRouter.profileRequest = request;

        // TODO add time and distance limits to routing, not just weight.
        // TODO apply walk and bike speeds and maxBike time.
        streetRouter.distanceLimitMeters = TransitLayer.DISTANCE_TABLE_SIZE_METERS; // FIXME arbitrary, and account for bike or car access mode
        streetRouter.setOrigin(request.fromLat, request.fromLon);
        streetRouter.route();
        streetRouter.dominanceVariable = StreetRouter.State.RoutingVariable.DURATION_SECONDS;
        accessTimes = new HashMap<>();
        accessTimes.put(mode, streetRouter.getReachedStops());
    }

    /** dump out all stop names */
    public String dumpStops (TIntIntMap stops) {
        if (DUMP_STOPS) {
            StringBuilder sb = new StringBuilder();

            stops.forEachEntry((stop, time) -> {
                String stopName = network.transitLayer.stopNames.get(stop);
                sb.append(String.format("%s (%d) at %sm %ss\n", stopName, stop, time / 60, time % 60));
                return true;
            });

            return sb.toString();
        } else {
            return "";
        }
    }

    /** Perform a McRAPTOR search and extract paths */
    public Collection<PathWithTimes> getPaths () {
        Collection<McRaptorState> states = route();
        // using a map here because even paths that are considered equal may have different times and we want to apply
        // strict dominance to equal paths
        Map<PathWithTimes, PathWithTimes> paths = new HashMap<>();

        states.forEach(s -> {
            PathWithTimes pwt = new PathWithTimes(s, network, request, accessTimes.get(s.accessMode), egressTimes.get(s.egressMode));

            if (!paths.containsKey(pwt) || paths.get(pwt).stats.avg > pwt.stats.avg)
                paths.put(pwt, pwt);
        });
        //states.forEach(s -> LOG.info("{}", s.dump(network)));

        LOG.info("{} states led to {} paths", states.size(), paths.size());

        paths.values().forEach(p -> LOG.info("{}", p.dump(network)));

        return new ArrayList<>(paths.values());
    }

    /** perform one round of the McRAPTOR search. Returns true if anything changed */
    private boolean doOneRound () {
        // optimization: on the last round, only explore patterns near the destination
        // in a point to point search
        if (round == MAX_ROUNDS && egressTimes != null)
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

            // used for frequency trips
            TObjectIntMap<StatePatternKey> boardTimesPerPatternSequence = new TObjectIntHashMap<>();

            TObjectIntMap<StatePatternKey> boardStopsPositionsPerPatternSequence = new TObjectIntHashMap<>();

            TripPattern pattern = network.transitLayer.tripPatterns.get(patIdx);
            RouteInfo routeInfo = network.transitLayer.routes.get(pattern.routeIndex);
            TransitModes mode = TransitLayer.getTransitModes(routeInfo.route_type);
            //skips trip patterns with trips which don't run on wanted date
            if (!pattern.servicesActive.intersects(servicesActive) ||
                //skips pattern with Transit mode which isn't wanted by profileRequest
                !request.transitModes.contains(mode)) {
                continue;
            }

            for (int stopPositionInPattern = 0; stopPositionInPattern < pattern.stops.length; stopPositionInPattern++) {
                int stop = pattern.stops[stopPositionInPattern];
                //Skips stops that don't allow wheelchair users if this is wanted in request
                if (request.wheelchair) {
                    if (!network.transitLayer.stopsWheelchair.get(stop)) {
                        continue;
                    }
                }

                // perform this check here so we don't needlessly loop over states at a stop that are all created by
                // getting off this pattern.
                boolean stopPreviouslyReached = bestStates.containsKey(stop);

                // get off the bus, if we can
                for (Map.Entry<StatePatternKey, McRaptorState> e : statesPerPatternSequence.entrySet()) {
                    int trip = tripsPerPatternSequence.get(e.getKey());
                    TripSchedule sched = pattern.tripSchedules.get(trip);

                    int boardStopPositionInPattern = boardStopsPositionsPerPatternSequence.get(e.getKey());

                    int arrival;

                    // we know we have no mixed schedule/frequency patterns, see check on boarding
                    if (sched.headwaySeconds != null) {
                        int travelTimeToStop = sched.arrivals[stopPositionInPattern] - sched.departures[boardStopPositionInPattern];
                        arrival = boardTimesPerPatternSequence.get(e.getKey()) + travelTimeToStop;
                    } else {
                        arrival = sched.arrivals[stopPositionInPattern];
                    }

                    if (addState(stop, boardStopPositionInPattern, stopPositionInPattern, arrival, patIdx, trip, e.getValue()))
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

                        if (pattern.hasFrequencies && pattern.hasSchedules) {
                            throw new IllegalStateException("McRAPTOR router does not support frequencies and schedules in the same trip pattern!");
                        }

                        // find a trip, if we can
                        int currentTrip = -1; // first increment lands at zero

                        StatePatternKey spk = new StatePatternKey(state);

                        if (pattern.hasSchedules) {
                            for (TripSchedule tripSchedule : pattern.tripSchedules) {
                                currentTrip++;
                                //Skips trips which don't run on wanted date
                                if (!servicesActive.get(tripSchedule.serviceCode) ||
                                    //Skip trips that can't be used with wheelchairs when wheelchair trip is requested
                                    (request.wheelchair && !tripSchedule.getFlag(TripFlag.WHEELCHAIR))) {
                                    continue;
                                }

                                int departure = tripSchedule.departures[stopPositionInPattern];
                                if (departure > state.time + BOARD_SLACK) {
                                    if (!statesPerPatternSequence.containsKey(spk) || tripsPerPatternSequence.get(spk) > currentTrip) {
                                        statesPerPatternSequence.put(spk, state);
                                        tripsPerPatternSequence.put(spk, currentTrip);
                                        boardTimesPerPatternSequence.put(spk, departure);
                                        boardStopsPositionsPerPatternSequence.put(spk, stopPositionInPattern);
                                    }

                                    // we found the best trip we can board at this stop, break loop regardless of whether
                                    // we decided to board it or continue on a trip coming from a previous stop.
                                    break;
                                }
                            }
                        } else if (pattern.hasFrequencies) {
                            for (TripSchedule tripSchedule : pattern.tripSchedules) {
                                currentTrip++;
                                if (!servicesActive.get(tripSchedule.serviceCode) ||
                                    //Skip trips that can't be used with wheelchairs when wheelchair trip is requested
                                    (request.wheelchair && !tripSchedule.getFlag(TripFlag.WHEELCHAIR))) {
                                    continue;
                                }

                                int earliestPossibleBoardTime = state.time + BOARD_SLACK;

                                // find a departure on this trip
                                for (int frequencyEntry = 0; frequencyEntry < tripSchedule.startTimes.length; frequencyEntry++) {
                                    int departure = tripSchedule.startTimes[frequencyEntry] +
                                            offsets.offsets.get(patIdx)[currentTrip][frequencyEntry] +
                                            tripSchedule.departures[stopPositionInPattern];

                                    int latestDeparture = tripSchedule.endTimes[frequencyEntry] +
                                            tripSchedule.departures[stopPositionInPattern];

                                    if (earliestPossibleBoardTime > latestDeparture) continue; // we're outside the time window

                                    while (departure < earliestPossibleBoardTime) departure += tripSchedule.headwaySeconds[frequencyEntry];

                                    // check again, because depending on the offset, the latest possible departure based
                                    // on end time may not actually occur
                                    if (departure > latestDeparture) continue;

                                    if (!statesPerPatternSequence.containsKey(spk) || boardTimesPerPatternSequence.get(spk) > departure) {
                                        statesPerPatternSequence.put(spk, state);
                                        tripsPerPatternSequence.put(spk, currentTrip);
                                        boardTimesPerPatternSequence.put(spk, departure);
                                        boardStopsPositionsPerPatternSequence.put(spk, stopPositionInPattern);
                                    }
                                }
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
        double walkSpeedMillimetersPerSecond = request.walkSpeed * 1000;
        for (int stop = touchedStops.nextSetBit(0); stop >= 0; stop = touchedStops.nextSetBit(stop + 1)) {
            TIntList transfers = network.transitLayer.transfersForStop.get(stop);

            for (McRaptorState state : bestStates.get(stop).getNonTransferStates()) {
                for (int transfer = 0; transfer < transfers.size(); transfer += 2) {
                    int toStop = transfers.get(transfer);
                    int distanceMillimeters = transfers.get(transfer + 1);
                    int walkTimeSeconds = (int)(distanceMillimeters / walkSpeedMillimetersPerSecond);
                    if (addState(toStop, -1, -1, state.time + walkTimeSeconds, -1, -1, state)) {
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

    /** propagate states to the destination in a point-to-point search */
    private Collection<McRaptorState> doPropagationToDestination() {
        McRaptorStateBag bag = createStateBag();

        egressTimes.forEach((mode, times) -> times.forEachEntry((stop, egressTime) -> {
            McRaptorStateBag bagAtStop = bestStates.get(stop);
            if (bagAtStop == null) return true;

            for (McRaptorState state : bagAtStop.getNonTransferStates()) {
                McRaptorState stateAtDest = new McRaptorState();
                stateAtDest.back = state;
                // walk to destination is transfer
                stateAtDest.pattern = -1;
                stateAtDest.trip = -1;
                stateAtDest.stop = -1;
                stateAtDest.accessMode = state.accessMode;
                stateAtDest.egressMode = mode;
                stateAtDest.time = state.time + egressTime;
                bag.add(stateAtDest);
            }

           return true;
        }));

        return bag.getBestStates();
    }

    private void doPropagationToPointSet (int departureTime) {
        int[] timesAtTargetsThisIteration = new int[pointSet.size()];
        Arrays.fill(timesAtTargetsThisIteration, RaptorWorker.UNREACHED);

        for (int stop = 0; stop < network.transitLayer.getStopCount(); stop++) {
            int[] distanceTable = pointSet.stopToPointDistanceTables.get(stop);

            if (distanceTable == null) continue;

            // find the best state at the stop
            McRaptorStateBag bag = bestStates.get(stop);

            if (bag == null) continue;

            // assume we're using fares as it doesn't make sense to do modeify-style suboptimal paths in Analyst
            McRaptorState best = null;
            for (McRaptorState state : bag.getNonTransferStates()) {
                // check if this state falls below the fare cutoff.
                // We generally try not to impose cutoffs at calculation time, but leaving two free cutoffs creates a grid
                // of possibilities that is too large to be stored.
                int fareAtState = network.fareCalculator.calculateFare(state);

                if (fareAtState > request.maxFare) {
                    continue;
                }

                if (best == null || state.time < best.time) best = state;
            }

            if (best == null) continue; // stop is unreachable

            // jagged array
            for (int i = 0; i < distanceTable.length; i += 2) {
                int target = distanceTable[i];
                int distance = distanceTable[i + 1];

                int timeAtTarget = (int) (best.time + distance / request.walkSpeed / 1000);

                if (timesAtTargetsThisIteration[target] > timeAtTarget) timesAtTargetsThisIteration[target] = timeAtTarget;
            }
        }

        for (int i = 0; i < timesAtTargetsThisIteration.length; i++) {
            if (timesAtTargetsThisIteration[i] != RaptorWorker.UNREACHED) timesAtTargetsThisIteration[i] -= departureTime;
        }

        timesAtTargetsEachIteration.add(timesAtTargetsThisIteration);
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

    private boolean addState (int stop, int boardStopPosition, int alightStopPosition, int time, int pattern, int trip, McRaptorState back) {
        return addState(stop, boardStopPosition, alightStopPosition, time, pattern, trip, back, back.accessMode);
    }


        /** Add a state */
    private boolean addState (int stop, int boardStopPosition, int alightStopPosition, int time, int pattern, int trip, McRaptorState back, LegMode accessMode) {
        /**
         * local pruning, and cutting off of excessively long searches
         * NB need to have cutoff be relative to toTime because otherwise when we do range-RAPTOR we'll have left over states
         * that are past the cutoff.
         */
        // cut off excessively long searches
        if (time > request.toTime + request.maxTripDurationMinutes * 60) return false;

        // local pruning iff in suboptimal point-to-point (Modeify) mode
        if (request.maxFare < 0 && time - request.suboptimalMinutes * 60 > bestTimesAtTargetByAccessMode.get(accessMode)) {
            return false;
        }

        if (back != null && back.time > time)
            throw new IllegalStateException("Attempt to decrement time in state!");

        McRaptorState state = new McRaptorState();
        state.stop = stop;
        state.boardStopPosition = boardStopPosition;
        state.alightStopPosition = alightStopPosition;
        state.time = time;
        state.pattern = pattern;
        state.trip = trip;
        state.back = back;
        state.round = round;
        state.accessMode = accessMode;

        // sanity check (anecdotally, this has no noticeable effect on speed)
        if (boardStopPosition >= 0) {
            TripPattern patt = network.transitLayer.tripPatterns.get(pattern);
            int boardStop = patt.stops[boardStopPosition];

            if (boardStop != back.stop) {
                LOG.error("Board stop position does not match board stop!");
            }

            if (stop != patt.stops[alightStopPosition]) {
                LOG.error("Alight stop position does not match alight stop!");
            }
        }

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

        if (!bestStates.containsKey(stop)) bestStates.put(stop, createStateBag());

        McRaptorStateBag bag = bestStates.get(stop);
        boolean optimal = bag.add(state);

        // target pruning: keep track of best time at destination
        if (egressTimes != null && optimal && pattern != -1) {
            // Save the worst egress time by any egress mode and use this for target pruning
            // we don't know what egress mode will be used when we do target pruning, above, so we just store the
            // best time for each access mode and the slowest egress mode
            int[] egressTimeWithSlowestEgressMode = new int[] { -1 };
            egressTimes.forEach((mode, times) -> {
                if (!times.containsKey(stop)) return;
                int timeAtDest = time + times.get(stop);
                egressTimeWithSlowestEgressMode[0] = Math.max(egressTimeWithSlowestEgressMode[0], timeAtDest);
            });

            if (egressTimeWithSlowestEgressMode[0] != -1 &&
                    egressTimeWithSlowestEgressMode[0] < bestTimesAtTargetByAccessMode.get(accessMode)) {
                bestTimesAtTargetByAccessMode.put(accessMode, egressTimeWithSlowestEgressMode[0]);
            }
        }

        return optimal;
    }

    /** Create a new McRaptorStateBag with properly-configured dominance */
    public McRaptorStateBag createStateBag () {
        if (request.maxFare >= 0) {
            if (network.fareCalculator == null) throw new IllegalArgumentException("Fares requested in ProfileRequest but no fare data loaded");

            return new McRaptorStateBag(() -> new FareDominatingList(network.fareCalculator));
        } else {
            return new McRaptorStateBag(() -> new SuboptimalDominatingList(request.suboptimalMinutes));
        }
    }

    /** run routing and return a result envelope */
    public ResultEnvelope routeEnvelope() {
        boolean isochrone = pointSet.pointSet instanceof WebMercatorGridPointSet;
        route();
        return propagatedTimesStore.makeResults(pointSet.pointSet, clusterRequest.includeTimes, !isochrone, isochrone);
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

        /**
         * What stop position are we at in the pattern?
         *
         * This is needed because the same stop can appear twice in a pattern, see #116.
         */
        public int boardStopPosition;

        /**
         * What stop position in this pattern did we board at?
         */
        public int alightStopPosition;

        /** the patterns used in this state */
        public int[] patterns = EMPTY_INT_ARRAY;

        public int patternHash;

        /** The mode used to access transit at the start of the trip implied by this state */
        public LegMode accessMode;
        public LegMode egressMode;

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

        public McRaptorStateBag(Supplier<DominatingList> factory) {
            this.best = factory.get();
            this.nonTransfer = factory.get();
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
                return Arrays.equals(state.patterns, ((StatePatternKey) o).state.patterns) &&
                        state.accessMode == ((StatePatternKey) o).state.accessMode;
            }

            return false;
        }
    }
}
