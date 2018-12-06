package com.conveyal.r5.profile.entur.rangeraptor.transit;

import com.conveyal.r5.profile.entur.api.request.RangeRaptorRequest;
import com.conveyal.r5.profile.entur.api.transit.AccessLeg;

import java.util.Collection;


/**
 * The transit calculator is used to calculate transit related stuff, like calculating
 * <em>earliest boarding time</em> and time-shifting the access legs.
 * <p/>
 * The calculator is shared between the state, worker and path mapping code. This
 * make the calculations consistent and let us hide the request parameters. Hiding the
 * request parameters ensure that this calculator is used.
 */
public final class TransitCalculator {
    private final Collection<AccessLeg> accessLegs;
    private final int boardSlackInSeconds;

    /**
     * Public calculator used fot unit-testing
     */
    public TransitCalculator(Collection<AccessLeg> accessLegs, int boardSlackInSeconds) {
        this.accessLegs = accessLegs;
        this.boardSlackInSeconds = boardSlackInSeconds;
    }

    public TransitCalculator(RangeRaptorRequest request) {
        this(request.accessLegs, request.boardSlackInSeconds);
    }

    public int earliestBoardTime(int arrivalTime) {
        return arrivalTime + boardSlackInSeconds;
    }

    public TimeInterval accessLegTimeIntervalAtStop(int stop, int transitBoardTime) {
        int arrivalTime = accessLegArrivalTime(transitBoardTime);
        int departureTime = originDepartureTime(transitBoardTime, accessLeg(stop).durationInSeconds());
        return new TimeInterval(departureTime, arrivalTime);
    }

    public int originDepartureTimeAtStop(int stop, int transitBoardTime) {
        return originDepartureTime(transitBoardTime, accessLeg(stop).durationInSeconds());
    }

    public int originDepartureTime(int firstTransitBoardTime, int accessLegDuration) {
        return accessLegArrivalTime(firstTransitBoardTime) - accessLegDuration;
    }

    public int accessLegArrivalTime(int firstTransitBoardTime) {
        return firstTransitBoardTime - boardSlackInSeconds;
    }

    private AccessLeg accessLeg(int stop) {
        for (AccessLeg accessLeg : accessLegs) {
            if(accessLeg.stop() == stop) {
                return accessLeg;
            }
        }
        throw new IndexOutOfBoundsException("No access leg found for stop: " + stop);
    }
}
