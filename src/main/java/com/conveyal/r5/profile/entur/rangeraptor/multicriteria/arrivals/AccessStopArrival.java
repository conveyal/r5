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

    /**
     * To
     */
    AbstractStopArrival<T> timeShifted(int boardTime) {
        return new TimeShiftedAccessArrival<>(this, boardTime);
    }

    /* private methods */

    private int departureTime(int transitBoardTime) {
        return calculator.originDepartureTime(transitBoardTime, accessDurationInSeconds);
    }

    private int arrivalTime(int transitBoardTime) {
        return calculator.accessLegArrivalTime(transitBoardTime);
    }


    /* private classes */

    private static class TimeShiftedAccessArrival<T extends TripScheduleInfo> extends AbstractStopArrival<T> {
        private AccessStopArrival original;

        private TimeShiftedAccessArrival(AccessStopArrival<T> origin, int transitBoardTime) {
            super(
                    origin.stop(),
                    origin.departureTime(transitBoardTime),
                    origin.arrivalTime(transitBoardTime),
                    origin.cost()
            );
            this.original = origin;
        }

        @Override
        public boolean arrivedByAccessLeg() {
            return true;
        }

        @Override
        public String toString() {
            return asString("original: " + original);
        }
    }
}
