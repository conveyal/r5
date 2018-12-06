package com.conveyal.r5.profile.entur.rangeraptor.multicriteria;

import com.conveyal.r5.profile.entur.api.transit.TripScheduleInfo;
import com.conveyal.r5.profile.entur.rangeraptor.multicriteria.arrivals.AbstractStopArrival;
import com.conveyal.r5.profile.entur.util.paretoset.ParetoSetWithMarker;

import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 *
 * @param <T> The TripSchedule type defined by the user of the range raptor API.
 */
class Stop<T extends TripScheduleInfo> extends ParetoSetWithMarker<AbstractStopArrival<T>> {

    Stop() {
        super(AbstractStopArrival.compareArrivalTimeRoundAndCost());
    }

    public Iterable<? extends AbstractStopArrival<T>> list(Predicate<AbstractStopArrival<T>> test) {
        return stream().filter(test).collect(Collectors.toList());
    }

    public Iterable<? extends AbstractStopArrival<T>> listCurrentAndLastRound(Predicate<AbstractStopArrival<T>> test) {
        return streamAfterMarker().filter(test).collect(Collectors.toList());
    }

}
