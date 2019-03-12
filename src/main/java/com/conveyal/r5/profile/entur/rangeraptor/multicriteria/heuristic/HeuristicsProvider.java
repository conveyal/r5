package com.conveyal.r5.profile.entur.rangeraptor.multicriteria.heuristic;

import com.conveyal.r5.profile.entur.api.transit.TripScheduleInfo;
import com.conveyal.r5.profile.entur.rangeraptor.RoundProvider;
import com.conveyal.r5.profile.entur.rangeraptor.path.DestinationArrivalPaths;
import com.conveyal.r5.profile.entur.rangeraptor.transit.CostCalculator;
import com.conveyal.r5.profile.entur.rangeraptor.view.Heuristics;

/**
 * A wrapper around {@link Heuristics} to cash elements to avoid recalculation
 * of heuristic properties.
 */
public final class HeuristicsProvider<T extends TripScheduleInfo> {
    private final Heuristics heuristics;
    private final RoundProvider roundProvider;
    private final DestinationArrivalPaths<T> paths;
    private final CostCalculator costCalculator;
    private final HeuristicAtStop[] stops;



    public HeuristicsProvider(Heuristics heuristics, RoundProvider roundProvider, DestinationArrivalPaths<T> paths, CostCalculator costCalculator) {
        this.heuristics = heuristics;
        this.roundProvider = roundProvider;
        this.costCalculator = costCalculator;
        this.paths = paths;
        this.stops = new HeuristicAtStop[heuristics.size()];
    }

    /**
     * This is used to make an optimistic guess for the best possible arrival at the destination,
     * using the given arrival and a pre-calculated heuristics.
     */
    public boolean qualify(int stop, int arrivalTime, int travelDuration, int cost) {
        HeuristicAtStop h = get(stop);

        if(h == null) {
            return false;
        }
        int minArrivalTime = arrivalTime + h.getMinTravelDuration();
        int minNumberOfTransfers = roundProvider.round() - 1 + h.getMinNumTransfers();
        int minTravelDuration = travelDuration + h.getMinTravelDuration();
        int minCost = cost + h.getMinCost();
        int departureTime = minArrivalTime - minTravelDuration;
        return paths.qualify(departureTime, minArrivalTime, minNumberOfTransfers, minCost);
    }


    /* private methods */

    private HeuristicAtStop createHeuristicAtStop(int bestTravelDuration, int bestNumOfTransfers) {
        return new HeuristicAtStop(
                bestTravelDuration,
                bestNumOfTransfers,
                costCalculator.calculateMinCost(bestTravelDuration, bestNumOfTransfers)
        );
    }

    public String rejectErrorMessage(int stop) {
        return get(stop) == null
                ? "The stop was not reached in the heuristic calculation."
                : get(stop).toString();

    }

    private HeuristicAtStop get(int stop) {
        if(stops[stop] == null && heuristics.reached(stop)) {
            stops[stop] = createHeuristicAtStop(heuristics.bestTravelDuration(stop), heuristics.bestNumOfTransfers(stop));
        }
        return stops[stop];
    }
}
