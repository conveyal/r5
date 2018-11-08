package com.conveyal.r5.profile.entur.rangeraptor.multicriteria;

import com.conveyal.r5.profile.entur.api.TripScheduleInfo;
import com.conveyal.r5.profile.entur.rangeraptor.multicriteria.arrivals.McStopArrivalState;
import com.conveyal.r5.profile.entur.util.paretoset.ParetoFunction;
import com.conveyal.r5.profile.entur.util.paretoset.ParetoSet;

import java.util.Iterator;
import java.util.function.Predicate;

class StopStateParetoSet<T extends TripScheduleInfo> extends ParetoSet<McStopArrivalState<T>> {

    StopStateParetoSet(ParetoFunction.Builder function) {
        super(function);
    }

    Iterable<? extends McStopArrivalState> listRound(int round) {
        return list(it -> it.round() == round);
    }

    Iterable<? extends McStopArrivalState<T>> list(Predicate<McStopArrivalState> test) {
        return () -> new Iterator<McStopArrivalState<T>>() {
            private int index = 0;
            private McStopArrivalState<T> it;


            @Override
            public boolean hasNext() {
                while (index < size() ) {
                    it = get(index);
                    ++index;
                    if (test.test(it)) {
                        return true;
                    }
                }
                return false;
            }
            @Override public McStopArrivalState<T> next() { return it; }
        };
    }
}
