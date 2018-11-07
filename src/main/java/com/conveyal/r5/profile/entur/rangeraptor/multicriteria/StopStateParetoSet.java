package com.conveyal.r5.profile.entur.rangeraptor.multicriteria;

import com.conveyal.r5.profile.entur.api.TripScheduleInfo;
import com.conveyal.r5.profile.entur.util.paretoset.ParetoFunction;
import com.conveyal.r5.profile.entur.util.paretoset.ParetoSet;

import java.util.Iterator;
import java.util.function.Predicate;

class StopStateParetoSet<T extends TripScheduleInfo> extends ParetoSet<McStopState<T>> {

    StopStateParetoSet(ParetoFunction.Builder function) {
        super(function);
    }

    Iterable<? extends McStopState> listRound(int round) {
        return list(it -> it.round() == round);
    }

    Iterable<? extends McStopState<T>> list(Predicate<McStopState> test) {
        return () -> new Iterator<McStopState<T>>() {
            private int index = 0;
            private McStopState<T> it;


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
            @Override public McStopState<T> next() { return it; }
        };
    }
}
