package com.conveyal.r5.speed_test;

import com.conveyal.r5.profile.mcrr.RangeRaptorWorkerState;
import com.conveyal.r5.profile.mcrr.RangeRaptorWorkerStateImpl;
import com.conveyal.r5.profile.mcrr.StopStateCollection;
import com.conveyal.r5.profile.mcrr.StopStatesIntArray;
import com.conveyal.r5.profile.mcrr.StopStatesStructArray;


enum StateFactory {
    parallel_int_arrays("int arrays") {
        @Override
        StopStateCollection createStopStateCollection(int nRounds, int nStops) {
            return new StopStatesIntArray(nRounds, nStops);
        }
    },
    struct_arrays("struct arrays") {
        @Override
        StopStateCollection createStopStateCollection(int nRounds, int nStops) {
            return new StopStatesStructArray(nRounds, nStops);
        }
    };
    final String name;

    StateFactory(String name) {
        this.name = name;
    }

    abstract StopStateCollection createStopStateCollection(int rounds, int stops);

    RangeRaptorWorkerState createWorkerState(int nRounds, int nStops, int earliestDepartureTime, int maxDurationSeconds, StopStateCollection stops) {
        return new RangeRaptorWorkerStateImpl(nRounds, nStops, earliestDepartureTime, maxDurationSeconds, stops);
    }

    StateFactory[] asArray() {
        return new StateFactory[]{ this };
    }

}
