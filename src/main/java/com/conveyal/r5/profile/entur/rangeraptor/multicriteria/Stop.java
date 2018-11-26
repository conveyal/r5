package com.conveyal.r5.profile.entur.rangeraptor.multicriteria;

import com.conveyal.r5.profile.entur.api.TripScheduleInfo;
import com.conveyal.r5.profile.entur.rangeraptor.multicriteria.arrivals.AbstractStopArrival;
import com.conveyal.r5.profile.entur.util.paretoset.ParetoSetWithMarker;

import java.util.function.Predicate;
import java.util.stream.Collectors;

class Stop<T extends TripScheduleInfo> extends ParetoSetWithMarker<AbstractStopArrival<T>> {

    Stop() {
        super(AbstractStopArrival.compareArrivalTimeRoundAndCost());
    }

    public Iterable<? extends AbstractStopArrival<T>> list(Predicate<AbstractStopArrival<T>> test) {
        return stream().filter(test).collect(Collectors.toList());
    }

    public Iterable<? extends AbstractStopArrival<T>> listCurrentAndLastRound(Predicate<AbstractStopArrival<T>> test) {
        return streamFromMark().filter(test).collect(Collectors.toList());
    }

}
