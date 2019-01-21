package com.conveyal.r5.profile.entur.rangeraptor.transit;

import com.conveyal.r5.profile.entur.api.request.RangeRaptorRequest;


/**
 * The transit calculator is used to calculate transit related stuff, like calculating
 * <em>earliest boarding time</em> and time-shifting the access legs.
 * <p/>
 * The calculator is shared between the state, worker and path mapping code. This
 * make the calculations consistent and let us hide the request parameters. Hiding the
 * request parameters ensure that this calculator is used.
 */
public final class TransitCalculator {
    private final int boardSlackInSeconds;

    /**
     * Public calculator used fot unit-testing
     */
    public TransitCalculator(int boardSlackInSeconds) {
        this.boardSlackInSeconds = boardSlackInSeconds;
    }

    public TransitCalculator(RangeRaptorRequest request) {
        this(request.boardSlackInSeconds);
    }

    public int earliestBoardTime(int arrivalTime) {
        return arrivalTime + boardSlackInSeconds;
    }

    public int latestArrivalTime(int departureTime) {
        return departureTime - boardSlackInSeconds;
    }

    public int originDepartureTime(int firstTransitBoardTime, int accessLegDuration) {
        return accessLegArrivalTime(firstTransitBoardTime) - accessLegDuration;
    }

    public int accessLegArrivalTime(int firstTransitBoardTime) {
        return firstTransitBoardTime - boardSlackInSeconds;
    }
}
