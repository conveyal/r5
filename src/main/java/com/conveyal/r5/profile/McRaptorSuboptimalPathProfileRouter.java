package com.conveyal.r5.profile;

import com.conveyal.r5.analyst.fare.FareBounds;
import com.conveyal.r5.analyst.fare.InRoutingFareCalculator;
import com.conveyal.r5.api.util.LegMode;
import com.conveyal.r5.api.util.TransitModes;
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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;
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

    public static final int[] EMPTY_INT_ARRAY = new int[0];

    private final boolean DUMP_STOPS = false;

    /** Use a list for the iterations since we aren't sure how many there will be (we're using random sampling over the departure minutes) */
    public List<int[]> timesAtStopsEachIteration = new ArrayList<>();

    private TransportNetwork network;
    private ProfileRequest request;
    private Map<LegMode, TIntIntMap> accessTimes;
    private Map<LegMode, TIntIntMap> egressTimes = null;
    private InRoutingFareCalculator.Collater collapseParetoSurfaceToTime;

    private FrequencyRandomOffsets offsets;

    private TIntObjectMap<McRaptorStateBag> bestStates = new TIntObjectHashMap<>();

    private int round = 0;
    private int departureTime;

    private BitSet touchedStops;
    private BitSet touchedPatterns;
    private BitSet patternsNearDestination;
    private BitSet servicesActive;
    // Used in creating the McRaptorStateBag; the type of list supplied determines the domination rules. Receives the departure time as an argument.
    private IntFunction<DominatingList> listSupplier;
    private MersenneTwister mersenneTwister;

    /** In order to properly do target pruning we store the best times at each target _by access mode_, so car trips don't quash walk trips */
    private TObjectIntMap<LegMode> bestTimesAtTargetByAccessMode = new TObjectIntHashMap<>(4, 0.95f, Integer.MAX_VALUE);

    /** if saveFinalStates is true, contains the final states for every departure time */
    public TIntObjectMap<Collection<McRaptorState>> finalStatesByDepartureTime = null;

    public final boolean saveFinalStates;

    /** backwards compatibility */
    public McRaptorSuboptimalPathProfileRouter (TransportNetwork network, ProfileRequest req, Map<LegMode,
            TIntIntMap> accessTimes, Map<LegMode, TIntIntMap> egressTimes, IntFunction<DominatingList> listSupplier,
                                                InRoutingFareCalculator.Collater collapseParetoSurfaceToTime) {
        this(network, req, accessTimes, egressTimes, listSupplier, collapseParetoSurfaceToTime, false);
    }

    /**
     *
     * @param network
     * @param req
     * @param accessTimes
     * @param egressTimes
     * @param listSupplier
     * @param collapseParetoSurfaceToTime
     * @param saveFinalStates if true, save the best states by departure time in the field finalStatesByDepartureTime.
     *                        egressTimes must not be null in this case.
     */
    public McRaptorSuboptimalPathProfileRouter (TransportNetwork network, ProfileRequest req, Map<LegMode,
            TIntIntMap> accessTimes, Map<LegMode, TIntIntMap> egressTimes, IntFunction<DominatingList> listSupplier,
                                                InRoutingFareCalculator.Collater collapseParetoSurfaceToTime,
                                                boolean saveFinalStates) {
        this.network = network;
        this.request = req;
        this.accessTimes = accessTimes;
        this.egressTimes = egressTimes;
        this.listSupplier = listSupplier;
        this.collapseParetoSurfaceToTime = collapseParetoSurfaceToTime;
        this.touchedStops = new BitSet(network.transitLayer.getStopCount());
        this.touchedPatterns = new BitSet(network.transitLayer.tripPatterns.size());
        this.patternsNearDestination = new BitSet(network.transitLayer.tripPatterns.size());
        this.servicesActive = network.transitLayer.getActiveServicesForDate(req.date);
        this.offsets = new FrequencyRandomOffsets(network.transitLayer);
        this.saveFinalStates = saveFinalStates;
        if (saveFinalStates) this.finalStatesByDepartureTime = new TIntObjectHashMap<>();

        // To make results repeatable from one run to the next, seed with some characteristic of the request itself,
        // e.g. (int) (request.fromLat * 1e9).  Leaving out an argument will make it use a combination of time and
        // the instance's identity hash code, which makes it truly random for all practical purposes.
        this.mersenneTwister = new MersenneTwister((int) (request.fromLat * 1e9));
    }

    /** Get a McRAPTOR state bag for every departure minute */
    public Collection<McRaptorState> route () {

        // Modeify does its own pre-computation of accessTimes, but Analysis does not
        // TODO MWC - I think this can be removed now, I believe analysis now does pre-compute access times.
        if (accessTimes == null) computeAccessTimes();

        long startTime = System.currentTimeMillis();

        // Optimization for modeify (PointToPointQuery): find patterns near destination
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

        List<McRaptorState> codominatingStatesToBeReturned = new ArrayList<>();

        // We start at end of time window and work backwards (which is what range-RAPTOR does, in case we
        // re-implement that here). We use a constrained random walk to choose which departure minutes to sample as we
        // work backward through the time window.  According to others (Owen and Jiang?), this is a good way to reduce
        // the number of samples without causing an issue with variance in results.  This value is the constraint
        // (upper limit) on the walk.
        // multiply by two because E[random] = 1/2 * max.
        if (request.monteCarloDraws == 200) {
            // 200 draws will take a really long time and is probably not what is desired. It is more likely the user simply
            // forgot to change the number of draws in the
            throw new IllegalArgumentException("Monte Carlo draws set to UI default, this is probably not what you want, exiting. " +
                    "Each draw in the fare-based router can be quite slow, so you probably want a smaller number. " +
                    "If you _really_ want 200 draws, maybe you'd be happy with 199 or 201, which will prevent " +
                    "this error?");
        }


        ArrayList<Integer> departureTimes = new ArrayList<>();

        while(departureTimes.size() != request.monteCarloDraws){
            departureTimes = generateDepartureTimesToSample(request);
        }

        for (int n = 0; n < departureTimes.size(); n++) {
            departureTime = departureTimes.get(n);

            // we're not using range-raptor so it's safe to change the schedule on each search
            offsets.randomize();

            bestStates.clear();
            touchedPatterns.clear();
            touchedStops.clear();
            // Round 0 is in essence non-transit access.
            round = 0;
            // final to allow use in the lambda function below
            final int finalDepartureTime = departureTime;

            // enqueue/relax access times, which are seconds of travel time (not clock time) by mode from the origin
            // to nearby stops
            accessTimes.forEach((mode, times) -> times.forEachEntry((stop, accessTime) -> {
                if (addState(stop, -1, -1, finalDepartureTime + accessTime, -1, -1, -1, null, mode))
                    touchedStops.set(stop);

                return true;
            }));

            markPatterns();

            round++;

            // NB the walk search is an initial round, so MAX_ROUNDS + 1
            while (doOneRound() && round < request.maxRides + 1);

            // TODO this means we wind up with some duplicated states.
            if (egressTimes != null) {
                // In a PointToPointQuery (for Modeify), egressTimes will already be computed
                Collection<McRaptorState> states = doPropagationToDestination(finalDepartureTime);
                codominatingStatesToBeReturned.addAll(states);
                if (saveFinalStates) finalStatesByDepartureTime.put(departureTime, states);
            }

            if (collapseParetoSurfaceToTime != null) {
                collateTravelTimes(departureTime);
            }

            LOG.info("minute {} / {}", n + 1, request.monteCarloDraws);
        }

        LOG.info("McRAPTOR took {}ms", System.currentTimeMillis() - startTime);

        // will be empty unless this is for a PointToPointQuery.
        return codominatingStatesToBeReturned;
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
        streetRouter.distanceLimitMeters = TransitLayer.WALK_DISTANCE_LIMIT_METERS; // FIXME arbitrary, and account for bike or car access mode
        streetRouter.setOrigin(request.fromLat, request.fromLon);
        streetRouter.route();
        streetRouter.quantityToMinimize = StreetRouter.State.RoutingVariable.DURATION_SECONDS;
        accessTimes = new HashMap<>();
        accessTimes.put(mode, streetRouter.getReachedStops());
    }

    /** dump out all stop names, for debugging */
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

        // A map to keep track of the best path among each group of paths using the same sequence of patterns.
        // We will often find multiple paths that board or transfer to the same patterns at different locations.
        // We only want to retain the best set of boarding, transfer, and alighting stops for a particular pattern sequence.
        // FIXME we are using a map here with unorthodox definitions of hashcode and equals to make them serve as map keys.
        // We should instead wrap PathWithTimes or copy the relevant fields into a PatternSequenceKey class.
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
        // make a protective copy of bestStates so we're not reading from the same structure we're writing to
        // Otherwise the router can ride multiple transit vehicles in a single round, if it explores the pattern of the first
        // before the pattern of the second
        TIntObjectMap<Collection<McRaptorState>> bestStatesBeforeRound = new TIntObjectHashMap<>();
        TIntObjectMap<Collection<McRaptorState>> bestNonTransferStatesBeforeRound = new TIntObjectHashMap<>();
        bestStates.forEachEntry((stop, bag) -> {
            bestStatesBeforeRound.put(stop, new ArrayList<>(bag.getBestStates()));
            bestNonTransferStatesBeforeRound.put(stop, new ArrayList<>(bag.getNonTransferStates()));
            return true; // continue iteration
        });

        // optimization: on the last round, only explore patterns near the destination in a point to point search
        if (round == request.maxRides && egressTimes != null)
            touchedPatterns.and(patternsNearDestination);

        for (int patIdx = touchedPatterns.nextSetBit(0); patIdx >= 0; patIdx = touchedPatterns.nextSetBit(patIdx + 1)) {
            // All states that have been propagated
            List<McRaptorState> states = new ArrayList<>();

            // The board stop position in the pattern for each state (not the R5 or GTFS stop ID)
            TObjectIntMap<McRaptorState> boardStopPositionInPattern = new TObjectIntHashMap<>();

            // The board time, for frequency trips
            TObjectIntMap<McRaptorState> boardTimeForFrequencyTrips = new TObjectIntHashMap<>();

            // The trip index in the pattern (not GTFS Trip ID) that produced each state
            TObjectIntMap<McRaptorState> tripIndicesInPattern = new TObjectIntHashMap<>();

            TripPattern pattern = network.transitLayer.tripPatterns.get(patIdx);
            RouteInfo routeInfo = network.transitLayer.routes.get(pattern.routeIndex);
            TransitModes mode = TransitLayer.getTransitModes(routeInfo.route_type);
            // skips trip patterns with trips which don't run on wanted date
            if (!pattern.servicesActive.intersects(servicesActive) ||
                // skips pattern with Transit mode which isn't wanted by profileRequest
                !request.transitModes.contains(mode)) {
                continue;
            }

            // ride along the entire pattern, picking up states as we go
            for (int stopPositionInPattern = 0; stopPositionInPattern < pattern.stops.length; stopPositionInPattern++) {
                int stop = pattern.stops[stopPositionInPattern];
                // Skips stops that don't allow wheelchair users if this is wanted in request
                if (request.wheelchair) {
                    if (!network.transitLayer.stopsWheelchair.get(stop)) {
                        continue;
                    }
                }

                // Perform this check here so we don't needlessly loop over states at a stop that are only created by
                // getting off this pattern. This optimization may limit the usefulness of R5 for a strict Class B
                // (touch all stations) Subway Challenge attempt (http://www.gricer.com/anysrc/anysrc.html).
                boolean stopReachedViaDifferentPattern = bestStatesBeforeRound.containsKey(stop);

                // get off the bus, if we can
                for (McRaptorState state : states) {
                    int tripIndexInPattern = tripIndicesInPattern.get(state);
                    TripSchedule sched = pattern.tripSchedules.get(tripIndexInPattern);
                    int boardStopPosition = boardStopPositionInPattern.get(state);
                    int arrival, boardTime;

                    // we know we have no mixed schedule/frequency patterns, see check on boarding
                    if (sched.headwaySeconds != null) {
                        int travelTimeToStop = sched.arrivals[stopPositionInPattern] - sched.departures[boardStopPosition];
                        boardTime = boardTimeForFrequencyTrips.get(state);
                        arrival = boardTime + travelTimeToStop;
                    } else {
                        arrival = sched.arrivals[stopPositionInPattern];
                        boardTime = sched.departures[boardStopPosition];
                    }

                    if (addState(stop, boardStopPosition, stopPositionInPattern, arrival, boardTime, patIdx,
                            tripIndexInPattern, state))
                        touchedStops.set(stop);
                }

                // get on the bus, if we can
                if (stopReachedViaDifferentPattern) {
                    STATES: for (McRaptorState state : bestStatesBeforeRound.get(stop)) {
                        if (state.round != round - 1) continue; // don't continually reexplore states

                        // don't reexplore patterns.
                        // NB checking and preventing reboarding any pattern that's been boarded in a previous
                        // round doesn't save a significant amount of search time (anecdotally), and forbids some rare
                        // but possible optimal routes that use the same pattern twice (e.g. transfering in Singapore
                        // from Downtown Line westbound at Jalan Besar to Rochor; see also Line 1 in Naples, or LU
                        // Circle Line in the vicinity of Paddington).
                        // if (prevPattern == patIdx) continue;

                        if (pattern.hasFrequencies && pattern.hasSchedules) {
                            throw new IllegalStateException("McRAPTOR router does not support frequencies and schedules in the same trip pattern!");
                        }

                        // find a trip, if we can
                        int currentTrip = -1; // first increment lands at zero


                        if (pattern.hasSchedules) {
                            for (TripSchedule tripSchedule : pattern.tripSchedules) {
                                currentTrip++;
                                //Skips trips which don't run on wanted date
                                if (!servicesActive.get(tripSchedule.serviceCode) ||
                                    //Skip trips that can't be used with wheelchairs when wheelchair trip is requested
                                    (request.wheelchair && !tripSchedule.getFlag(TripFlag.WHEELCHAIR))) {
                                    continue;
                                }
                                // clock time for trip departing a stop
                                int departure = tripSchedule.departures[stopPositionInPattern];
                                if (departure > state.time + BOARD_SLACK) {
                                    // boarding is possible here
                                    states.add(state);
                                    tripIndicesInPattern.put(state, currentTrip);
                                    boardStopPositionInPattern.put(state, stopPositionInPattern);

                                    // we found the best trip we can board at this stop based on travel time (we know this because trips
                                    // are sorted by departure time from first stop), break loop regardless of whether
                                    // we decided to board it or continue on a trip coming from a previous stop.

                                    // NB there is an assumption here that a user will take the first vehicle that comes
                                    // on the desired pattern. It is possible to imagine a situation in which this is not
                                    // completely correct. If there are peak and off-peak fares, it may make sense to arrive
                                    // at a transfer point and allow a on-peak vehicle to pass in order to get on the next vehicle
                                    // which just so happens to arrive after peak. I do not doubt that someone, somewhere, does this.
                                    // There are reasons to do this at a transfer point. Suppose that there are peak and off-peak
                                    // fares for a rail system but not a connecting bus system (e.g., WMATA in DC). Suppose that the bus only
                                    // comes every hour. If you take the 8:30 AM (hourly) bus, you arrive at the rail station at 8:50 - still in peak time.
                                    // However, if you allow the 8:55 on-peak train to pass and take the off-peak 9:01, you stand to save some money.
                                    // You can't leave your house later, because the feeder bus isn't coming again until 9:30.
                                    // This isn't a problem for the almost certainly more common situation of people delaying
                                    // their trips to save money, as that should be accounted for by the time window (and if you
                                    // wanted to consider a trip that nominally departed at 8:30 but involved waiting to depart until 9:00
                                    // to get the best fare, you could achieve that through post-processing.
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
                                    // we have to check all trips and frequency entries because, unlike
                                    // schedule-based trips, these are not sorted
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

                                    states.add(state);
                                    tripIndicesInPattern.put(state, currentTrip);
                                    boardTimeForFrequencyTrips.put(state, departure);
                                    boardStopPositionInPattern.put(state, stopPositionInPattern);
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

            // okay to use bestStates directly here, it doesn't allow the router to ride two transit vehicles in one round.
            // because doTransfers only creates transfer states, it does not affect nonTransfer states.
            for (McRaptorState state : bestStates.get(stop).getNonTransferStates()) {
                for (int transfer = 0; transfer < transfers.size(); transfer += 2) {
                    int toStop = transfers.get(transfer);
                    int distanceMillimeters = transfers.get(transfer + 1);
                    int walkTimeSeconds = (int)(distanceMillimeters / walkSpeedMillimetersPerSecond);
                    if (addState(toStop, -1, -1, state.time + walkTimeSeconds, -1, -1, -1, state)) {
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
    private Collection<McRaptorState> doPropagationToDestination(int departureTime) {
        McRaptorStateBag bag = createStateBag(departureTime);

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

    private ArrayList<Integer> generateDepartureTimesToSample (ProfileRequest request) {
        // See Owen and Jiang 2016 (unfortunately no longer available online), add between f / 2 and
        // f + f / 2, where f is the mean step.
        int randomWalkStepMean = (request.toTime - request.fromTime) / request.monteCarloDraws;
        int randomWalkStepWidthOneSided = randomWalkStepMean / 2;

        ArrayList<Integer> departureTimes = new ArrayList<>();

        for (int departureTime = request.fromTime + mersenneTwister.nextInt(randomWalkStepMean);
             departureTime < request.toTime;
             departureTime += mersenneTwister.nextInt(randomWalkStepMean) + randomWalkStepWidthOneSided) {
            departureTimes.add(departureTime);
        }

        return departureTimes;

    }

    private void collateTravelTimes(int departureTime) {
        int[] timesAtStopsThisIteration = new int[network.transitLayer.getStopCount()];
        Arrays.fill(timesAtStopsThisIteration, FastRaptorWorker.UNREACHED);

        for (int stop = 0; stop < network.transitLayer.getStopCount(); stop++) {
            // find the best state at the stop
            McRaptorStateBag bag = bestStates.get(stop);

            if (bag == null) continue;
            int bestClockTimeGivenConstraint = collapseParetoSurfaceToTime.collate(bag.getNonTransferStates(),
                            departureTime + request.maxTripDurationMinutes * 60);
            if (bestClockTimeGivenConstraint < timesAtStopsThisIteration[stop]){
                timesAtStopsThisIteration[stop] = bestClockTimeGivenConstraint;
            }
        }

        for (int i = 0; i < timesAtStopsThisIteration.length; i++) {
            if (timesAtStopsThisIteration[i] != FastRaptorWorker.UNREACHED) timesAtStopsThisIteration[i] -= departureTime;
        }

        timesAtStopsEachIteration.add(timesAtStopsThisIteration);
    }

    public int[][] getBestTimes() {
        return timesAtStopsEachIteration.toArray(new int[timesAtStopsEachIteration.size()][]);
    }

    /** Mark patterns at touched stops, to be explored in a subsequent round */
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

    private boolean addState (int stop, int boardStopPosition, int alightStopPosition, int time, int boardTime, int
            pattern, int trip, McRaptorState back) {
        return addState(stop, boardStopPosition, alightStopPosition, time, boardTime, pattern, trip, back, back
                .accessMode);
    }


        /** Add a state */
    private boolean addState (int stop, int boardStopPosition, int alightStopPosition, int time, int boardTime, int
            pattern, int trip, McRaptorState back, LegMode accessMode) {
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
        state.boardTime = boardTime;
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

        if (!bestStates.containsKey(stop)) bestStates.put(stop, createStateBag(departureTime));

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
    public McRaptorStateBag createStateBag (int departureTime) {
        return new McRaptorStateBag(() -> listSupplier.apply(departureTime));
    }

    /**
     * This is the McRAPTOR state, which stores a way to get to a stop in a round. It is an object,
     * so there is a certain level of indirection, but note that all of its members are primitives so the entire object
     * can be packed.
     */
    public static class McRaptorState {
        /** what is the previous state? */
        public McRaptorState back;

        /** What is the clock time of this state (seconds since midnight) */
        public int time;

        /** Board time of ride used to reach this stop */
        public int boardTime;

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

        /** The mode used to access transit at the start of the trip implied by this state */
        public LegMode accessMode;
        public LegMode egressMode;

        /**
         * The fare to get to this state. Ideally, this wouldn't be here, as it only applies to fare-based routing, not
         * other McRaptor DominatingLists, but this is the easiest place to put it so that it isn't being recalculated
         * all the time (which can be slow if there are table lookups involved).
         */
        public FareBounds fare;

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

        /** best states for which the preceding step was not a transfer via the street network. States in this list
         * could be reached by multiple transfers farther back in the itinerary, but we need a separate list for
         * stops reached by a direct egress from a transit vehicle without intervening walking along the street
         * network.  This is to avoid circumventing the egress walk limit. */
        private DominatingList nonTransfer;

        public McRaptorStateBag(Supplier<DominatingList> factory) {
            this.best = factory.get();
            this.nonTransfer = factory.get();
        }

        /** try adding state to the best DominatingList, and to the nonTransfer dominating list if the last step in
         * this state was not a transfer */
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
}
