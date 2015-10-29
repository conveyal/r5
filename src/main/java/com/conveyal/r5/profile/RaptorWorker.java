package com.conveyal.r5.profile;

import com.conveyal.r5.streets.LinkedPointSet;
import com.conveyal.r5.transit.TransitLayer;
import com.conveyal.r5.transit.TripPattern;
import com.conveyal.r5.transit.TripSchedule;
import com.google.protobuf.CodedOutputStream;
import gnu.trove.iterator.TIntIntIterator;
import gnu.trove.iterator.TIntIterator;
import gnu.trove.list.TIntList;
import gnu.trove.map.TIntIntMap;
import gnu.trove.map.hash.TIntIntHashMap;
import com.conveyal.r5.analyst.cluster.TaskStatistics;
import com.conveyal.r5.profile.PropagatedTimesStore.ConfidenceCalculationMethod;
import com.conveyal.r5.streets.PointSetTimes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.stream.IntStream;

/**
 * This is an exact copy of RaptorWorker that's being modified to work with (new) TransitNetworks
 * instead of (old) Graphs. We can afford the maintainability nightmare of duplicating so much code because this is
 * intended to completely replace the old class soon.
 *
 * A RaptorWorker carries out RAPTOR searches on a pre-filtered, compacted representation of all the trips running
 * during a given time window. It originated as a rewrite of our RAPTOR code that would use "thin workers", allowing
 * computation by a generic function-execution service like AWS Lambda. The gains in efficiency were significant enough
 * that RaptorWorkers are now used in the context of a full-size OTP server executing spatial analysis tasks.
 *
 * We can imagine that someday all OTP searches, including simple point-to-point searches, may be carried out on such
 * compacted tables, including both the transit and street searches (via a compacted street network in a column store
 * or struct-like byte array using something like the FastSerialization library).
 *
 * This implements the RAPTOR algorithm; see http://research.microsoft.com/pubs/156567/raptor_alenex.pdf
 */
public class RaptorWorker {

    private static final Logger LOG = LoggerFactory.getLogger(RaptorWorker.class);
    public static final int UNREACHED = Integer.MAX_VALUE;
    static final int MAX_DURATION = 120 * 60;

    /**
     * The number of randomized frequency schedule draws to take for each minute of the search.
     *
     * We loop over all departure minutes and do a schedule search, and then run this many Monte Carlo
     * searches with randomized frequency schedules within each minute. It does not need to be particularly
     * high as it happens each minute, and there is likely a lot of repetition in the scheduled service
     * (i.e. many minutes look like each other), so several minutes' monte carlo draws are effectively
     * pooled.
     */
    public static final int MONTE_CARLO_COUNT_PER_MINUTE = 1;

    /** If there are no schedules, the number of Monte Carlo draws to take */
    public static final int TOTAL_MONTE_CARLO_COUNT = 99;

    /** minimum slack time to board transit */
    public static final int BOARD_SLACK = 60;

    int max_time = 0;
    int round = 0;
    List<int[]> timesPerStopPerRound;
    int[] timesPerStop;
    int[] bestTimes;


    /**
     * The previous pattern used to get to this stop, parallel to bestTimes. Used to apply transfer rules. This is conceptually
     * similar to the "parent pointer" used in the RAPTOR paper to allow reconstructing paths. This could
     * be used to reconstruct a path (although potentially not the one that was used to get to a particular
     * location, as a later round may have found a faster but more-transfers way to get there). A path
     * reconstructed this way will tbus be optimal in the earliest-arrival sense but may not have the
     * fewest transfers; in fact, it will tend not to.
     *
     * Consider the case where there is a slower one-seat ride and a quicker route with a transfer
     * to get to a transit center. At the transit center you board another vehicle. If it turns out
     * that you still catch that vehicle at the same time regardless of which option you choose,
     * general utility theory would suggest that you would choose the one seat ride due to a) the
     * inconvenience of the transfer and b) the fact that most people have a smaller disutility for
     * in-vehicle time than waiting time, especially if the waiting is exposed to the elements, etc.
     *
     * However, this implementation will find the more-transfers trip because it doesn't know where you're
     * going from the transit center, whereas true RAPTOR would find both. It's not non-optimal in the
     * earliest arrival sense, but it's also not the only optimal option.
     *
     * All of that said, we could reconstruct paths simply by storing one more parallel array with
     * the index of the stop that you boarded a particular pattern at. Then we can do the typical
     * reverse-optimization step.
     */
    int[] previousPatterns;

    /** The best times for reaching stops via transit rather than via a transfer from another stop */
    int[] bestNonTransferTimes;

    TransitLayer data;

    /** stops touched this round */
    BitSet stopsTouched;

    /** stops touched any round this minute */
    BitSet allStopsTouched;

    BitSet patternsTouched;

    private ProfileRequest req;

    private long totalPropagationTime = 0;

    private FrequencyRandomOffsets offsets;

    public PropagatedTimesStore propagatedTimesStore;

    public LinkedPointSet targets;

    public BitSet servicesActive;

    public RaptorWorker(TransitLayer data, LinkedPointSet targets, ProfileRequest req) {
        this.data = data;
        // these should only reflect the results of the (deterministic) scheduled search
        int nStops = data.streetVertexForStop.size();
        this.bestTimes = new int[nStops];
        this.bestNonTransferTimes = new int[nStops];
        this.previousPatterns = new int[nStops];
        Arrays.fill(previousPatterns, -1);
        allStopsTouched = new BitSet(nStops);
        stopsTouched = new BitSet(nStops);
        patternsTouched = new BitSet(data.tripPatterns.size());

        this.targets = targets;

        this.servicesActive = data.getActiveServicesForDate(req.date);

        this.req = req; 
        Arrays.fill(bestTimes, UNREACHED); // initialize once here and reuse on subsequent iterations.
        Arrays.fill(bestNonTransferTimes, UNREACHED);
        offsets = new FrequencyRandomOffsets(data);
    }

    public void advance () {
        round++;
        //        timesPerStop = new int[data.nStops];
        //        Arrays.fill(timesPerStop, UNREACHED);
        //        timesPerStopPerRound.add(timesPerStop);
        // uncomment to disable range-raptor
        //Arrays.fill(bestTimes, UNREACHED);
    }

    /**
     * @param accessTimes a map from transit stops to the time it takes to reach those stops
     * @param nonTransitTimes the time to reach all targets without transit. Targets can be vertices or points/samples.
     */
    public PropagatedTimesStore runRaptor (TIntIntMap accessTimes, PointSetTimes nonTransitTimes, TaskStatistics ts) {
        long beginCalcTime = System.currentTimeMillis();
        TIntIntMap initialStops = new TIntIntHashMap();
        TIntIntIterator initialIterator = accessTimes.iterator();
        while (initialIterator.hasNext()) {
            initialIterator.advance();
            int stopIndex = initialIterator.key();
            int accessTime = initialIterator.value();
            initialStops.put(stopIndex, accessTime);
        }

        if (propagatedTimesStore == null)
            propagatedTimesStore = new PropagatedTimesStore(targets.size());

        // optimization: if no schedules, only run Monte Carlo
        int fromTime = req.fromTime;
        int monteCarloDraws = MONTE_CARLO_COUNT_PER_MINUTE;
        if (!data.hasSchedules) {
            // only do one iteration
            fromTime = req.toTime - 60;
            monteCarloDraws = TOTAL_MONTE_CARLO_COUNT;
        }

        // if no frequencies, don't run Monte Carlo
        int iterations = (req.toTime - fromTime - 60) / 60 + 1;

        // if we do Monte Carlo, we do more iterations. But we only do monte carlo when we have frequencies.
        // So only update the number of iterations when we're actually going to use all of them, to
        // avoid uninitialized arrays.
        // if we multiply when we're not doing monte carlo, we'll end up with too many iterations.
        if (data.hasFrequencies)
            // we add 2 because we do two "fake" draws where we do min or max instead of a monte carlo draw
            iterations *= (monteCarloDraws + 2);

        ts.searchCount = iterations;

        // Iterate backward through minutes (range-raptor) taking a snapshot of router state after each call
        int[][] timesAtTargetsEachIteration = new int[iterations][targets.size()];

        // TODO don't hardwire timestep below
        ts.timeStep = 60;

        // times at targets from scheduled search
        int[] scheduledTimesAtTargets = new int[targets.size()];
        Arrays.fill(scheduledTimesAtTargets, UNREACHED);

        // current iteration
        int iteration = 0;

        // FIXME this should be changed to tolerate a zero-width time range
        for (int departureTime = req.toTime - 60, n = 0; departureTime >= fromTime; departureTime -= 60, n++) {
            if (n % 15 == 0) {
                LOG.info("minute {}", n);
            }

            // Run the search on scheduled routes.
            this.runRaptorScheduled(initialStops, departureTime);
            this.doPropagation(bestNonTransferTimes, scheduledTimesAtTargets, departureTime);

            // Copy in the pre-transit times; we don't want to force people to ride transit instead of walking a block.
            for (int i = 0; i < scheduledTimesAtTargets.length; i++) {
                int nonTransitTravelTime = nonTransitTimes.getTravelTimeToPoint(i);
                int nonTransitClockTime = nonTransitTravelTime + departureTime;
                if (nonTransitTravelTime != UNREACHED && nonTransitClockTime < scheduledTimesAtTargets[i]) {
                    scheduledTimesAtTargets[i] = nonTransitClockTime;
                }
            }

            // Run any searches on frequency-based routes.
            if (data.hasFrequencies) {
                for (int i = 0; i < monteCarloDraws + 2; i++) {
                    // make copies for just this search. We need copies because we can't use dynamic
                    // programming/range-raptor with randomized schedules
                    int[] bestTimesCopy = Arrays.copyOf(bestTimes, bestTimes.length);
                    int[] bestNonTransferTimesCopy = Arrays
                            .copyOf(bestNonTransferTimes, bestNonTransferTimes.length);
                    int[] previousPatternsCopy = Arrays
                            .copyOf(previousPatterns, previousPatterns.length);

                    offsets.randomize();

                    if (i == 0)
                        this.runRaptorFrequency(departureTime, bestTimesCopy, bestNonTransferTimesCopy,
                            previousPatternsCopy, BoardingAssumption.BEST_CASE);
                    else if (i == 1)
                        this.runRaptorFrequency(departureTime, bestTimesCopy, bestNonTransferTimesCopy,
                                previousPatternsCopy, BoardingAssumption.WORST_CASE);
                    else
                        this.runRaptorFrequency(departureTime, bestTimesCopy, bestNonTransferTimesCopy,
                                previousPatternsCopy, BoardingAssumption.RANDOM);

                    // do propagation
                    int[] frequencyTimesAtTargets = timesAtTargetsEachIteration[iteration++];
                    System.arraycopy(scheduledTimesAtTargets, 0, frequencyTimesAtTargets, 0,
                            scheduledTimesAtTargets.length);
                    // updates timesAtTargetsEachIteration directly because it has a reference into the array.
                    this.doPropagation(bestNonTransferTimesCopy, frequencyTimesAtTargets,
                            departureTime);

                    // convert to elapsed time
                    for (int t = 0; t < frequencyTimesAtTargets.length; t++) {
                        if (frequencyTimesAtTargets[t] != UNREACHED)
                            frequencyTimesAtTargets[t] -= departureTime;
                    }
                }
            } else {
                final int dt = departureTime;
                timesAtTargetsEachIteration[iteration++] = IntStream.of(scheduledTimesAtTargets)
                        .map(i -> i != UNREACHED ? i - dt : i)
                        .toArray();
            }
        }

        // make sure we filled the array, otherwise results are garbage.
        // This implies a bug in OTP, but it has happened in the past when we did
        // not set the number of iterations correctly.
        // iteration should be incremented past end of array by ++ in assignment above
        if (iteration != iterations)
            throw new IllegalStateException("Iterations did not completely fill output array");

        long calcTime = System.currentTimeMillis() - beginCalcTime;
        LOG.info("calc time {}sec", calcTime / 1000.0);
        LOG.info("  propagation {}sec", totalPropagationTime / 1000.0);
        LOG.info("  raptor {}sec", (calcTime - totalPropagationTime) / 1000.0);
        ts.propagation = (int) totalPropagationTime;
        ts.transitSearch = (int) (calcTime - totalPropagationTime);
        //dumpVariableByte(timesAtTargetsEachMinute);
        // we can use min_max here as we've also run it once with best case and worst case board,
        // so the best and worst cases are meaningful.
        propagatedTimesStore.setFromArray(timesAtTargetsEachIteration, ConfidenceCalculationMethod.MIN_MAX);
        return propagatedTimesStore;
    }

    public void dumpVariableByte(int[][] array) {
        try {
            FileOutputStream fos = new FileOutputStream("/Users/abyrd/results.dat");
            CodedOutputStream cos = CodedOutputStream.newInstance(fos);
            cos.writeUInt32NoTag(array.length);
            for (int[] subArray : array) {
                cos.writeUInt32NoTag(subArray.length);
                for (int val : subArray) {
                    cos.writeInt32NoTag(val);
                }
            }
            fos.close();
        } catch (FileNotFoundException e) {
            LOG.error("File not found for dumping raptor results", e);
        } catch (IOException e) {
            LOG.error("IOException dumping raptor results", e);
        }
    }

    /** Run a raptor search not using frequencies */
    public void runRaptorScheduled (TIntIntMap initialStops, int departureTime) {
        // Arrays.fill(bestTimes, UNREACHED); hold on to old state
        max_time = departureTime + MAX_DURATION;
        round = 0;
        advance(); // go to first round
        patternsTouched.clear(); // clear patterns left over from previous calls.
        allStopsTouched.clear();
        stopsTouched.clear();
        // Copy initial stops over to the first round
        TIntIntIterator iterator = initialStops.iterator();
        while (iterator.hasNext()) {
            iterator.advance();
            int stopIndex = iterator.key();
            int time = iterator.value() + departureTime;
            // note not setting bestNonTransferTimes here because the initial walk is effectively a "transfer"
            bestTimes[stopIndex] = Math.min(time, bestTimes[stopIndex]);
            markPatternsForStop(stopIndex);
        }

        // Anytime a round updates some stops, move on to another round
        while (doOneRound(bestTimes, bestNonTransferTimes, previousPatterns, false, null)) {
            advance();
        }
    }

    /** Run a RAPTOR search using frequencies */
    public void runRaptorFrequency (int departureTime, int[] bestTimes, int[] bestNonTransferTimes, int[] previousPatterns, BoardingAssumption boardingAssumption) {
        max_time = departureTime + MAX_DURATION;
        round = 0;
        advance(); // go to first round
        patternsTouched.clear(); // clear patterns left over from previous calls.
        allStopsTouched.clear();
        stopsTouched.clear();

        // we need to mark every reachable stop here, because the network is changing randomly.
        // It is entirely possible that the first trip in an itinerary does not change, but trips
        // further down do.
        IntStream.range(0, bestTimes.length).filter(i -> bestTimes[i] != UNREACHED).forEach(
                this::markPatternsForStop);

        // Anytime a round updates some stops, move on to another round
        while (doOneRound(bestTimes, bestNonTransferTimes, previousPatterns, true, boardingAssumption)) {
            advance();
        }
    }

    /** perform one round, possibly using frequencies with the defined boarding assumption (which is ignored and may be set to null if useFrequencies == false) */
    public boolean doOneRound (int[] bestTimes, int[] bestNonTransferTimes, int[] previousPatterns, boolean useFrequencies, BoardingAssumption boardingAssumption) {
        //LOG.info("round {}", round);
        stopsTouched.clear(); // clear any stops left over from previous round.
        PATTERNS: for (int p = patternsTouched.nextSetBit(0); p >= 0; p = patternsTouched.nextSetBit(p+1)) {
            //LOG.info("pattern {} {}", p, data.patternNames.get(p));
            TripPattern timetable = data.tripPatterns.get(p);
            int stopPositionInPattern = -1; // first increment will land this at zero

            int bestFreqBoardTime = Integer.MAX_VALUE;
            int bestFreqBoardStop = -1;
            TripSchedule bestFreqTrip = null; // this is which _trip_ we are on if we are riding a frequency-based service. It is
            // not important which frequency entry we used to board it.

            // first look for a frequency entry
            if (useFrequencies) {
                for (int stopIndex : timetable.stops) {
                    stopPositionInPattern += 1;

                    // the time at this stop if we remain on board a vehicle we had already boarded
                    int remainOnBoardTime;
                    if (bestFreqTrip != null) {
                        // we are already aboard a trip, stay on board
                        remainOnBoardTime = bestFreqBoardTime + bestFreqTrip.arrivals[stopPositionInPattern] -
                                bestFreqTrip.departures[bestFreqBoardStop];
                    } else {
                        // we cannot remain on board as we are not yet on board
                        remainOnBoardTime = Integer.MAX_VALUE;
                    }

                    // the time at this stop if we board a new vehicle
                    if (bestTimes[stopIndex] != UNREACHED) {
                        for (int tripScheduleIdx = 0; tripScheduleIdx < timetable.tripSchedules.size(); tripScheduleIdx++) {
                            TripSchedule ts = timetable.tripSchedules.get(tripScheduleIdx);
                            if (ts.headwaySeconds == null || !servicesActive.get(ts.serviceCode))
                                continue; // not a frequency trip

                            // TODO: boarding assumptions, transfer rules?
                            // figure out best board time for each frequency entry on this trip, and choose the best of those
                            // this is a valid thing to do and doesn't exhibit the problems we've seen in the past with
                            // monte carlo simulations where we take the min of several random variables (e.g. when we used
                            // to randomly choose a boarding wait on each boarding, see https://github.com/opentripplanner/OpenTripPlanner/issues/2072 and
                            // https://github.com/opentripplanner/OpenTripPlanner/issues/2065).
                            // In this case it is completely valid to assume that any frequency entries are uncorrelated,
                            // and it's only a problem when they overlap anyhow.
                            int boardTime = Integer.MAX_VALUE;
                            FREQUENCY_ENTRIES: for (int freqEntryIdx = 0; freqEntryIdx < ts.headwaySeconds.length; freqEntryIdx++) {
                                int boardTimeThisEntry;

                                if (boardingAssumption == BoardingAssumption.BEST_CASE) {
                                    if (bestTimes[stopIndex] + BOARD_SLACK > ts.endTimes[freqEntryIdx] + ts.departures[stopPositionInPattern])
                                        continue FREQUENCY_ENTRIES; // it's too late, can't board.

                                    // best case boarding time is now, or when this frequency entry starts, whichever is later
                                    boardTimeThisEntry = Math.max(bestTimes[stopIndex] + BOARD_SLACK, ts.startTimes[freqEntryIdx] + ts.departures[stopPositionInPattern]);
                                }
                                else if (boardingAssumption == BoardingAssumption.WORST_CASE) {
                                    // worst case: cannot board this entry if there is not the full headway remaining before the end of the entry, we
                                    // might miss the vehicle.
                                    if (bestTimes[stopIndex] + BOARD_SLACK > ts.endTimes[freqEntryIdx] + ts.departures[stopPositionInPattern] - ts.headwaySeconds[freqEntryIdx])
                                        continue FREQUENCY_ENTRIES;

                                    boardTimeThisEntry = Math.max(bestTimes[stopIndex] + BOARD_SLACK + ts.headwaySeconds[freqEntryIdx],
                                            ts.startTimes[freqEntryIdx] + ts.departures[stopPositionInPattern] + ts.headwaySeconds[freqEntryIdx]);
                                }
                                else {
                                    // should not throw NPE, if it does something is messed up because these should
                                    // only be null for scheduled trips on a trip pattern with some frequency trips.
                                    // we shouldn't be considering scheduled trips here.
                                    int offset = offsets.offsets.get(p)[tripScheduleIdx][freqEntryIdx];

                                    // earliest board time is start time plus travel time plus offset
                                    boardTimeThisEntry = ts.startTimes[freqEntryIdx] +
                                            ts.departures[stopPositionInPattern] +
                                            offset;

                                    if (boardTimeThisEntry < 0)
                                        LOG.info("oops");

                                    // we're treating this as a scheduled trip even though the schedule will change on the next
                                    // iteration. Figure out when the last trip is, which is just the start time plus the headway multiplied
                                    // number of trips.

                                    // first figure out how many trips there are. note that this can change depending on the offset.
                                    // int math, java will round down
                                    int nTripsThisEntry = (ts.endTimes[freqEntryIdx] - ts.startTimes[freqEntryIdx] + offset) /
                                            ts.headwaySeconds[freqEntryIdx];

                                    // the is the last time a vehicle leaves the terminal
                                    int latestTerminalDeparture = ts.startTimes[freqEntryIdx] +
                                            nTripsThisEntry * ts.headwaySeconds[freqEntryIdx];

                                    if (latestTerminalDeparture > ts.endTimes[freqEntryIdx])
                                        LOG.error("latest terminal departure is after end of frequency entry. this is a bug.");

                                    int latestBoardTime = latestTerminalDeparture + ts.departures[stopPositionInPattern];

                                    while (boardTimeThisEntry < bestTimes[stopIndex] + BOARD_SLACK) {
                                        boardTimeThisEntry += ts.headwaySeconds[freqEntryIdx];

                                        if (boardTimeThisEntry > latestTerminalDeparture) {
                                            // can't board this frequency entry
                                            continue FREQUENCY_ENTRIES;
                                        }
                                    }
                                }

                                // if we haven't continued the outer loop yet, we could potentially board this stop
                                boardTime = Math.min(boardTime, boardTimeThisEntry);
                            }

                            if (boardTime != Integer.MAX_VALUE && boardTime < remainOnBoardTime) {
                                // make sure we board the best frequency entry at a stop
                                if (bestFreqBoardStop == stopPositionInPattern && bestFreqBoardTime < boardTime)
                                    continue;

                                // board this vehicle
                                // note: this boards the trip with the lowest headway at the given time.
                                // if there are overtaking trips all bets are off.
                                bestFreqBoardTime = boardTime;
                                bestFreqBoardStop = stopPositionInPattern;
                                bestFreqTrip = ts;
                                // note that we do not break the loop in case there's another frequency entry that is better
                            }
                        }
                    }

                    // save the remain on board time. If we boarded a new trip then we know that the
                    // remain on board time must be larger than the arrival time at the stop so will
                    // not be saved; no need for an explicit check.
                    if (remainOnBoardTime != Integer.MAX_VALUE && remainOnBoardTime < max_time) {
                        if (bestNonTransferTimes[stopIndex] > remainOnBoardTime) {
                            bestNonTransferTimes[stopIndex] = remainOnBoardTime;

                            stopsTouched.set(stopIndex);
                            allStopsTouched.set(stopIndex);

                            if (bestTimes[stopIndex] > remainOnBoardTime) {
                                bestTimes[stopIndex] = remainOnBoardTime;
                                previousPatterns[stopIndex] = p;
                            }
                        }
                    }
                }

                // don't mix frequencies and timetables
                // TODO should we have this condition here?
                if (bestFreqTrip != null)
                    continue PATTERNS;
            }

            // perform scheduled search
            TripSchedule onTrip = null;
            stopPositionInPattern = -1;
            for (int stopIndex : timetable.stops) {
                stopPositionInPattern += 1;
                if (onTrip == null) {
                    // We haven't boarded yet
                    if (bestTimes[stopIndex] == UNREACHED) {
                        continue; // we've never reached this stop, we can't board.
                    }
                    // Stop has been reached before. Attempt to board here.

                    // handle overtaking trips by doing linear search through all trips
                    int bestBoardTimeThisStop = Integer.MAX_VALUE;
                    for (TripSchedule trip : timetable.tripSchedules) {
                        if (trip.headwaySeconds != null || !servicesActive.get(trip.serviceCode))
                            // frequency trip;
                            continue;

                        int dep = trip.departures[stopPositionInPattern];
                        if (dep > bestTimes[stopIndex] + BOARD_SLACK && dep < bestBoardTimeThisStop) {
                            onTrip = trip;
                            bestBoardTimeThisStop = dep;
                        }
                    }

                    continue; // boarded or not, we move on to the next stop in the sequence
                } else {
                    // We're on board a trip.
                    int arrivalTime = onTrip.arrivals[stopPositionInPattern];

                    if (arrivalTime > max_time)
                        // cut off the search, don't continue searching this pattern
                        continue PATTERNS;

                    if (arrivalTime < bestNonTransferTimes[stopIndex]) {
                        bestNonTransferTimes[stopIndex] = arrivalTime;

                        stopsTouched.set(stopIndex);
                        allStopsTouched.set(stopIndex);

                        if (arrivalTime < bestTimes[stopIndex]) {
                            bestTimes[stopIndex] = arrivalTime;
                            previousPatterns[stopIndex] = p;
                        }

                    }

                    // Check whether we can switch to an earlier trip. This could be due to an overtaking trip (in
                    // this case we will have set bestTimes above), or (more likely) because there was a faster way to
                    // get to a stop further down the line.
                    for (TripSchedule trip : timetable.tripSchedules) {
                        if (trip.headwaySeconds != null || !servicesActive.get(trip.serviceCode))
                            // frequency trip or not running today
                            continue;

                        // use bestTime not bestNonTransferTimes to allow transferring to this trip later on down the route
                        if (trip.departures[stopPositionInPattern] > bestTimes[stopIndex] + BOARD_SLACK &&
                                trip.departures[stopPositionInPattern] < onTrip.departures[stopPositionInPattern]) {
                            onTrip = trip;
                        }
                    }
                }
            }
        }
        doTransfers(bestTimes, bestNonTransferTimes, previousPatterns);
        return !patternsTouched.isEmpty();
    }

    /**
     * Apply transfers.
     * Mark all the patterns passing through these stops and any stops transferred to.
     */
    private void doTransfers(int[] bestTimes, int[] bestNonTransferTimes, int[] previousPatterns) {
        patternsTouched.clear();
        for (int stop = stopsTouched.nextSetBit(0); stop >= 0; stop = stopsTouched.nextSetBit(stop + 1)) {
            // TODO this is reboarding every trip at every stop.
            markPatternsForStop(stop);
            int fromTime = bestNonTransferTimes[stop];

            TIntList transfers = data.transfersForStop.get(stop);
            // transfers stored as jagged array, loop two at a time
            for (int i = 0; i < transfers.size(); i += 2) {
                int toStop = transfers.get(i);
                int distance = transfers.get(i + 1);
                int toTime = fromTime + (int) (distance / req.walkSpeed);
                if (toTime < max_time && toTime < bestTimes[toStop]) {
                    bestTimes[toStop] = toTime;
                    previousPatterns[toStop] = previousPatterns[stop];
                    markPatternsForStop(toStop);
                }
            }
        }
    }

    /**
     * Propagate from the transit network to the street network.
     * Uses allStopsTouched to determine from whence to propagate.
     *
     * This is valid both for randomized frequencies and for schedules, because the stops that have
     * been updated will be in allStopsTouched.
     */
    public void doPropagation (int[] timesAtTransitStops, int[] timesAtTargets, int departureTime) {
        long beginPropagationTime = System.currentTimeMillis();

        // Record distances to each sample or intersection
        // We need to propagate all the way to samples (or intersections if there are no samples)
        // when doing repeated RAPTOR.
        // Consider the situation where there are two parallel transit lines on
        // 5th Street and 6th Street, and you live on A Street halfway between 5th and 6th.
        // Both lines run at 30 minute headways, but they are exactly out of phase, and for the
        // purposes of this conversation both go the same place with the same in-vehicle travel time.
        // Thus, even though the lines run every 30 minutes, you never experience a wait of more than
        // 15 minutes because you are clever when you choose which line to take. The worst case at each
        // transit stop is much worse than the worst case at samples. While unlikely, it is possible that
        // a sample would be able to reach these two stops within the walk limit, but that the two
        // intersections it is connected to cannot reach both.

        // only loop over stops that were touched this minute
        for (int s = allStopsTouched.nextSetBit(0); s >= 0; s = allStopsTouched.nextSetBit(s + 1)) {
            // it's safe to use the best time at this stop for any number of transfers, even in range-raptor,
            // because we allow unlimited transfers. this is slightly different from the original RAPTOR implementation:
            // we do not necessarily compute all pareto-optimal paths on (journey time, number of transfers).
            int baseTimeSeconds = timesAtTransitStops[s];
            if (baseTimeSeconds != UNREACHED) {
                int[] targets = this.targets.stopTrees.get(s);
                if (targets == null) {
                    continue;
                }
                // Targets contains pairs of (targetIndex, time).
                // The cache has time in seconds rather than distance to avoid costly floating-point divides and integer casts here.
                for (int i = 0; i < targets.length; ) { // Counter i is incremented in two places below.
                    int targetIndex = targets[i++]; // Increment i after read
                    int propagated_time = baseTimeSeconds + targets[i++]; // Increment i after read

                    if (propagated_time < departureTime) {
                        //LOG.error("negative propagated time, will crash shortly");
                    }

                    if (timesAtTargets[targetIndex] > propagated_time) {
                        timesAtTargets[targetIndex] = propagated_time;
                    }
                }
            }
        }
        totalPropagationTime += (System.currentTimeMillis() - beginPropagationTime);
    }

    /** Mark all the patterns passing through the given stop. */
    private void markPatternsForStop(int stop) {
        TIntList patterns = data.patternsForStop.get(stop);
        for (TIntIterator it = patterns.iterator(); it.hasNext();) {
            patternsTouched.set(it.next());
        }
    }

}
