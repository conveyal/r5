package com.conveyal.r5.profile.entur.rangeraptor.multicriteria.heuristic;


import com.conveyal.r5.profile.entur.api.transit.TransferLeg;
import com.conveyal.r5.profile.entur.api.transit.TripScheduleInfo;
import com.conveyal.r5.profile.entur.rangeraptor.multicriteria.DestinationHeuristic;
import com.conveyal.r5.profile.entur.rangeraptor.standard.BestTimesWorkerState;
import com.conveyal.r5.profile.entur.rangeraptor.transit.CostCalculator;
import com.conveyal.r5.profile.entur.rangeraptor.transit.SearchContext;
import com.conveyal.r5.profile.entur.rangeraptor.transit.TransitCalculator;


/**
 * Tracks the state of a Range Raptor search to build heristic data to be used in a multi-criteria search.
 *
 * @param <T> The TripSchedule type defined by the user of the range raptor API.
 */
public class CalculateHeuristicWorkerState<T extends TripScheduleInfo> extends BestTimesWorkerState<T> {
    private int earliestDepartureTime;
    private final Heuristic[] heuristics;
    private final CostCalculator costCalculator;

    /**
     * create a BestTimes Range Raptor State for given context.
     */
    public CalculateHeuristicWorkerState(SearchContext<T> ctx) {
        this(ctx.nRounds(), ctx.transit().numberOfStops(), ctx.calculator(), ctx.costCalculator());
    }

    private CalculateHeuristicWorkerState(int nRounds, int nStops, TransitCalculator calculator, CostCalculator costCalculator) {
        super(nRounds, nStops, calculator);
        this.costCalculator = costCalculator;
        this.heuristics = new Heuristic[nStops];
    }

    @Override
    protected void setupIteration2(int iterationDepartureTime) {
        earliestDepartureTime = iterationDepartureTime;
    }

    @Override
    protected void setInitialTime(final int stop, final int arrivalTime, int durationInSeconds) {
        setMinRoundNumber(stop, 0);
    }

    @Override
    protected void setNewBestTransitTime(int stop, int alightTime, T trip, int boardStop, int boardTime, boolean newBestOverall) {
        setMinRoundNumber(stop, round());
    }

    @Override
    protected void rejectNewBestTransitTime(int stop, int alightTime, T trip, int boardStop, int boardTime) {
        setMinRoundNumber(stop, round());
    }

    @Override
    protected void setNewBestTransferTime(int fromStop, int arrivalTime, TransferLeg transferLeg) {
        setMinRoundNumber(transferLeg.stop(), round());
    }

    @Override
    protected void rejectNewBestTransferTime(int fromStop, int arrivalTime, TransferLeg transferLeg) {
        setMinRoundNumber(transferLeg.stop(), round());
    }

    private void setMinRoundNumber(int stop, int round) {
        Heuristic h = heuristics[stop];

        if(h== null) {
            heuristics[stop] = new Heuristic(round);
        }
    }

    public DestinationHeuristic[] heuristic() {
        for (int i = 0; i < heuristics.length; i++) {
            Heuristic h = heuristics[i];

            if(h == null) {
                continue;
            }
            h.setMinTravelTime(earliestDepartureTime - bestTime(i));
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