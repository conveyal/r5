package com.conveyal.r5.macau.router;

import com.conveyal.r5.macau.distribution.Distribution;
import com.conveyal.r5.macau.distribution.DistributionOfSum;
import com.conveyal.r5.macau.distribution.KroneckerDelta;
import com.conveyal.r5.macau.distribution.OrDistribution;
import com.conveyal.r5.macau.distribution.RightShift;
import com.conveyal.r5.macau.distribution.UniformDistribution;
import com.conveyal.r5.transit.TransitLayer;
import com.conveyal.r5.transit.TripPattern;
import com.conveyal.r5.transit.TripSchedule;
import gnu.trove.list.TIntList;
import gnu.trove.map.TIntIntMap;

import static com.google.common.base.Preconditions.checkState;

public class MacauRouter {

    private final TransitLayer transitLayer;
    private final int nStops;
    private final int nRounds = 4;

    // group these into RoundState, with one StopState for transfers and one for rides?
    private MacauState[] transferStates;
    private MacauState[] rideStates;

    public MacauRouter (TransitLayer transitLayer) {
        this.transitLayer = transitLayer;
        nStops = transitLayer.getStopCount();
        transferStates = new MacauState[nStops];
        rideStates = new MacauState[nStops];
    }

    public void initStopAccess (TIntIntMap timesToStops) {
        MacauState transferState = new MacauState(nStops);
        timesToStops.forEachEntry((stop, timeSeconds) -> transferState.add(stop, new KroneckerDelta(timeSeconds)));
        transferStates[0] = transferState;
    }

    public void route () {
        for (int round = 0; round < nRounds; round++) {
            processTransfers(round);
            processTransit(round);
        }
    }

    /**
     * If there was a previous round with transit rides, make transfers from the stops reached in that round.
     */
    private void processTransfers (int round) {
        if (round == 0) {
            // Just validate that transfers were initialized.
            checkState(transferStates[0] != null && !(transferStates[0].updatedStops.isEmpty()));
            return;
        }
        MacauState in = rideStates[round - 1];
        MacauState out = in.copy();
        for (int stop = 0; stop < nStops; stop++) {
            OrDistribution distributionsAtStop = in.distributionsAtStops[stop];
            if (distributionsAtStop == null) {
                continue;
            }
            // The trivial "loop transfer" of staying in place was created by the state copy operation above.
            TIntList packedTransfers = transitLayer.transfersForStop.get(stop);
            if (packedTransfers != null) {
                int i = 0;
                while (i < packedTransfers.size()) {
                    int targetStop = packedTransfers.get(i++);
                    int accessTime = packedTransfers.get(i++);
                    // TODO Somehow record that this is due to a transfer, recording paths as well as times
                    out.add(targetStop, new RightShift(distributionsAtStop, accessTime));
                }
            }
        }
        transferStates[round] = out;
    }

    /**
     * Assuming that transfers (or access) are already processed in round n, find any improvements on transit ride time.
     */
    private void processTransit (int round) {
        // TODO find all patterns through updated stops instead of iterating all patterns
        MacauState in = transferStates[round];
        MacauState out = in.copy();
        for (TripPattern pattern : transitLayer.tripPatterns) {
            for (TripSchedule tripSchedule : pattern.tripSchedules) {
                if (tripSchedule.nFrequencyEntries() != 1) {
                    throw new UnsupportedOperationException("Currently we're only routing on single-schedule pure frequencies.");
                }
                int headwaySeconds = tripSchedule.headwaySeconds[0];
                UniformDistribution waitTimeDistribution = new UniformDistribution(0, headwaySeconds);

                // Instead of O(nStops^2) madness we carry a bag of distributions down the line in an OrDistribution.
                // Then there should be an optimized merge in out.add():
                // adding an OrDistribution to another OrDistribution can just do:
                // target.distribs.addAdd(source.distribs)
                OrDistribution bag = new OrDistribution();
                for (int s = 0; s < pattern.stops.length; s++) {
                    int stop = pattern.stops[s];

                    int arrivalSeconds = tripSchedule.arrivals[s];
                    int departureSeconds = tripSchedule.departures[s];
                    if (s > 0) {
                        // Make shift and add conditional on bag having terms.
                        bag.rightShiftTerms(arrivalSeconds - tripSchedule.departures[s - 1]);
                        out.add(stop, bag);
                    }
                    if (departureSeconds != arrivalSeconds) {
                        bag.rightShiftTerms(departureSeconds - arrivalSeconds);
                    }

                    // Add any arrival at current stop to bag
                    OrDistribution inDistribution = in.distributionsAtStops[stop];
                    if (inDistribution != null) {
                        // TODO Add slack?
                        Distribution departureDistribution = new DistributionOfSum(inDistribution, waitTimeDistribution);
                        bag.addAndPrune(departureDistribution);
                    }

                    // GAH here you run into the problem that in any one MC simulation, the schedule is fixed.
                    // You don't encounter a random distribution of departure times at each stop on the same trip.
                    // Consider the case where you arrive earlier but with a broader distribution at one stop,
                    // later but with a narrower distribution at another stop. Both of these can board the same trip.
                    // How do you propagate the ride down the stops? I guess this depends on the properties of the
                    // "or" operation.
                }
            }
        }
        rideStates[round] = out;
    }

    /**
     * Given the distribution of arrival times at the given stop, what is the distribution of times we'd board vehicles?
     * This needs to handle pure frequency and scheduled routes, and needs to handle cases where each trip that will be
     * boarded will run at a different speed.
     *
     * Maybe we should do this in a generalized way, but have a "just in time" distribution optimizer that compacts
     * some complicated distributions down to empirical lookup tables. Either a field in all distributions or some
     * shared lookup table from distribution instances to arrays.
     *
     * Let's handle only pure frequencies for now, and then add schedules when frequencies are working.
     * Herein lies the problem with storing clock times instead of elapsed times in the distributions: the latter is
     * much more efficient for networks with more pure frequencies, the former for networks with more schedules.
     *
     * Note also that each frequency entry will have a single wait time distribution and a single ride time distribution
     * (both of which can be reused at all stops down the line).
     */
    private Distribution waitDistribution (Distribution arrival, TripPattern pattern, int stop) {
        return null;
    }

}
