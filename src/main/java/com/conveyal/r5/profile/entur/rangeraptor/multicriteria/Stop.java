package com.conveyal.r5.profile.entur.rangeraptor.multicriteria;

import com.conveyal.r5.profile.entur.api.TripScheduleInfo;
import com.conveyal.r5.profile.entur.rangeraptor.multicriteria.arrivals.AbstractStopArrival;
import com.conveyal.r5.profile.entur.util.paretoset.ParetoFunction;
import com.conveyal.r5.profile.entur.util.paretoset.ParetoSet;

import java.util.Iterator;
import java.util.function.Predicate;

class Stop<T extends TripScheduleInfo> extends ParetoSet<AbstractStopArrival<T>> {

    Stop(ParetoFunction[] function) {
        super(function);
    }

    Iterable<? extends AbstractStopArrival> listRound(int round) {
        return list(it -> it.round() == round);
    }

    Iterable<? extends AbstractStopArrival<T>> list(Predicate<AbstractStopArrival> test) {
        return () -> new Iterator<AbstractStopArrival<T>>() {
            private int index = 0;
            private AbstractStopArrival<T> it;


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
            @Override public AbstractStopArrival<T> next() { return it; }
        };
    }
}
