package com.conveyal.r5.profile.entur.rangeraptor.standard.heuristics;

import com.conveyal.r5.profile.entur.rangeraptor.WorkerLifeCycle;
import com.conveyal.r5.profile.entur.rangeraptor.standard.besttimes.BestTimes;
import com.conveyal.r5.profile.entur.rangeraptor.standard.transfers.BestNumberOfTransfers;
import com.conveyal.r5.profile.entur.rangeraptor.transit.TransitCalculator;
import com.conveyal.r5.profile.entur.rangeraptor.view.Heuristics;


/**
 * The responsibility of this class is to play the {@link Heuristics} role.
 * It wrap the internal state, and transform the internal model to
 * provide the needed functionality.
 */
public class HeuristicsAdapter implements Heuristics {
    private int originDepartureTime = -1;
    private final BestTimes bestTimes;
    private final BestNumberOfTransfers bestNumberOfTransfers;
    private final TransitCalculator calculator;

    public HeuristicsAdapter(
            BestTimes bestTimes,
            BestNumberOfTransfers bestNumberOfTransfers,
            TransitCalculator calculator,
            WorkerLifeCycle lifeCycle
    ) {
        this.bestTimes = bestTimes;
        this.bestNumberOfTransfers = bestNumberOfTransfers;
        this.calculator = calculator;
        lifeCycle.onSetupIteration(this::setUpIteration);
    }

    private void setUpIteration(int departureTime) {
        if (this.originDepartureTime > 0) {
            throw new IllegalStateException(
                    "You should only run one iteration to calculate heuristics, this is because we use " +
                    "the origin departure time to calculate the travel duration at the end of the search."
            );
        }
        this.originDepartureTime = departureTime;
    }

    @Override
    public boolean reached(int stop) {
        return bestTimes.isStopReached(stop);
    }

    @Override
    public int bestTravelDuration(int stop) {
        return calculator.duration(originDepartureTime, bestTimes.time(stop));
    }

    @Override
    public int bestNumOfTransfers(int stop) {
        return bestNumberOfTransfers.minNumberOfTransfers(stop);
    }

    @Override
    public int size() {
        return bestTimes.size();
    }
}
