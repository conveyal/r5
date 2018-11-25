package com.conveyal.r5.profile.entur.rangeraptor.multicriteria.arrivals;


import com.conveyal.r5.profile.entur.api.TripScheduleInfo;
import com.conveyal.r5.profile.entur.rangeraptor.transit.TransitCalculator;


/**
 * Represent a access stop arrival.
 */
public final class AccessStopArrival<T extends TripScheduleInfo> extends AbstractStopArrival<T> {
    private final int accessDurationInSeconds;
    private final TransitCalculator calculator;

    public AccessStopArrival(int stop, int departureTime, int accessDurationInSeconds, int cost, TransitCalculator calculator) {
        super(stop, departureTime, departureTime + accessDurationInSeconds, cost);
        this.calculator = calculator;
        this.accessDurationInSeconds = accessDurationInSeconds;
    }

    public boolean arrivedByAccessLeg() {
        return true;
    }

    @Override
    public int departureTimeAccess(int transitBoardTime) {
        return calculator.originDepartureTime(transitBoardTime, accessDurationInSeconds);
    }

    @Override
    public int arrivalTimeAccess(int transitBoardTime) {
        return calculator.accessLegArrivalTime(transitBoardTime);
    }
}
