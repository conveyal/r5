package com.conveyal.r5.otp2._shared;

import com.conveyal.r5.otp2.api.TestTripSchedule;
import com.conveyal.r5.otp2.api.view.ArrivalView;
import com.conveyal.r5.otp2.util.TimeUtils;

public class Egress {
    private final int departureTime;
    private final int arrivalTime;
    private final ArrivalView<TestTripSchedule> previous;

    Egress(int departureTime, int arrivalTime, ArrivalView<TestTripSchedule> previous) {
        this.departureTime = departureTime;
        this.arrivalTime = arrivalTime;
        this.previous = previous;
    }
    public int additionalCost(){ return 100; }
    public int departureTime() { return departureTime; }
    public int arrivalTime() { return arrivalTime; }
    public ArrivalView<TestTripSchedule> previous() { return previous; }

    @Override
    public String toString() {
            return String.format(
                    "%s { Rnd: %d, Stop: %d, Time: %s (%s), Cost: %d }",
                    getClass().getSimpleName(),
                    previous.round(),
                    previous.stop(),
                    TimeUtils.timeToStrCompact(arrivalTime),
                    TimeUtils.timeToStrCompact(departureTime),
                    previous.cost() + additionalCost()
            );
    }
}
