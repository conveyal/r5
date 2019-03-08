package com.conveyal.r5.profile.entur.rangeraptor.multicriteria.heuristic;


import com.conveyal.r5.profile.entur.api.path.Path;
import com.conveyal.r5.profile.entur.api.transit.TransferLeg;
import com.conveyal.r5.profile.entur.api.transit.TripScheduleInfo;
import com.conveyal.r5.profile.entur.rangeraptor.RoundProvider;
import com.conveyal.r5.profile.entur.rangeraptor.WorkerLifeCycle;
import com.conveyal.r5.profile.entur.rangeraptor.multicriteria.DestinationHeuristic;
import com.conveyal.r5.profile.entur.rangeraptor.standard.StopArrivalsState;
import com.conveyal.r5.profile.entur.rangeraptor.standard.besttimes.BestTimes;
import com.conveyal.r5.profile.entur.rangeraptor.transit.CostCalculator;
import com.conveyal.r5.profile.entur.rangeraptor.transit.SearchContext;

import java.util.Collection;
import java.util.Collections;


/**
 * Tracks the state of a Range Raptor search to build heuristic data to be used in a multi-criteria search.
 *
 * @param <T> The TripSchedule type defined by the user of the range raptor API.
 */
public class CalculateHeuristicWorkerState<T extends TripScheduleInfo> implements StopArrivalsState<T> {
    private final BestTimes bestTimes;
    private final RoundProvider roundProvider;
    private final Heuristic[] heuristics;
    private final CostCalculator costCalculator;
    private int earliestDepartureTime;

    /**
     * create a BestTimes Range Raptor State for given context.
     */
    public CalculateHeuristicWorkerState(SearchContext<T> ctx, BestTimes bestTimes) {
        this(
                ctx.transit().numberOfStops(),
                ctx.roundProvider(),
                bestTimes,
                ctx.costCalculator(),
                ctx.lifeCycle()
        );
    }

    public CalculateHeuristicWorkerState(
            int nStops,
            RoundProvider roundProvider,
            BestTimes bestTimes,
            CostCalculator costCalculator,
            WorkerLifeCycle lifeCycle
    ) {
        this.roundProvider = roundProvider;
        this.bestTimes = bestTimes;
        this.costCalculator = costCalculator;
        this.heuristics = new Heuristic[nStops];
        lifeCycle.onSetupIteration(this::setupIteration);
    }

    @Override
    public void setInitialTime(final int stop, final int arrivalTime, int durationInSeconds) {
        setMinRoundNumber(stop, 0);
    }

    @Override
    public Collection<Path<T>> extractPaths() {
        return Collections.emptyList();
    }

    /**
     * This implementation does NOT return the "best time in the previous round"; It returns the
     * overall "best time" across all rounds including the current.
     * <p/>
     * This is a simplification, *bestTimes* might get updated during the current round; Hence
     * leading to a new boarding at the alight stop in the same round. If we do not count rounds
     * or track paths, this is OK.
     * <P/>
     * Because this rarely happens and heuristics does not need to be exact - it only need to be
     * optimistic. So if we arrive at a stop one or two rounds to early, the only effect is that
     * the "number of transfers" for those stops is to small - or what we call a optimistic estimate.
     * <p/>
     * The "arrival time" is calculated correctly.
     */
    @Override
    public int bestTimePreviousRound(int stop) {
        return bestTimes.time(stop);
    }

    @Override
    public void setNewBestTransitTime(int stop, int alightTime, T trip, int boardStop, int boardTime, boolean newBestOverall) {
        setMinRoundNumber(stop, round());
    }

    @Override
    public void rejectNewBestTransitTime(int stop, int alightTime, T trip, int boardStop, int boardTime) {
        setMinRoundNumber(stop, round());
    }

    @Override
    public void setNewBestTransferTime(int fromStop, int arrivalTime, TransferLeg transferLeg) {
        setMinRoundNumber(transferLeg.stop(), round());
    }

    @Override
    public void rejectNewBestTransferTime(int fromStop, int arrivalTime, TransferLeg transferLeg) {
        setMinRoundNumber(transferLeg.stop(), round());
    }



    /* Private methods */

    private void setupIteration(int iterationDepartureTime) {
        earliestDepartureTime = iterationDepartureTime;
    }

    private void setMinRoundNumber(int stop, int round) {
        Heuristic h = heuristics[stop];

        if(h== null) {
            heuristics[stop] = new Heuristic(round);
        }
    }

    private int round() {
        return roundProvider.round();
    }


    public DestinationHeuristic[] heuristic() {
        for (int i = 0; i < heuristics.length; i++) {
            Heuristic h = heuristics[i];

            if(h == null) {
                continue;
            }
            h.setMinTravelTime(earliestDepartureTime - bestTimes.time(i));
            h.setMinCost(costCalculator.calculateMinCost(h.getMinTravelTime(), h.getMinNumTransfers()));
        }
        return heuristics;
    }

    private static class Heuristic implements DestinationHeuristic {
        private int minTravelTime;
        private int minNumTransfers;
        private int minCost;

        Heuristic(int minNumTransfers) {
            this.minNumTransfers = minNumTransfers;
        }

        void setMinTravelTime(int minTravelTime) {
            this.minTravelTime = minTravelTime;
        }

        void setMinCost(int minCost) {
            this.minCost = minCost;
        }

        @Override
        public int getMinTravelTime() {
            return minTravelTime;
        }

        @Override
        public int getMinNumTransfers() {
            return minNumTransfers;
        }

        @Override
        public int getMinCost() {
            return minCost;
        }
    }
}