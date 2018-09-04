package com.conveyal.r5.speed_test;

import com.conveyal.r5.profile.ProfileRequest;
import com.conveyal.r5.profile.mcrr.PathBuilder;
import com.conveyal.r5.profile.mcrr.PathBuilderCursorBased;
import com.conveyal.r5.profile.mcrr.RangeRaptorWorker;
import com.conveyal.r5.profile.mcrr.RangeRaptorWorkerState;
import com.conveyal.r5.profile.mcrr.RangeRaptorWorkerStateImpl;
import com.conveyal.r5.profile.mcrr.RaptorWorkerTransitDataProvider;
import com.conveyal.r5.profile.mcrr.StopStateCollection;
import com.conveyal.r5.profile.mcrr.StopStatesIntArray;
import com.conveyal.r5.profile.mcrr.StopStatesStructArray;
import com.conveyal.r5.profile.mcrr.Worker;

import java.util.Arrays;


enum ProfileFactory {
    int_arrays("int arrays"),
    struct_arrays("struct arrays") {
        @Override
        StopStateCollection createStopStateCollection(int nRounds, int nStops) {
            return new StopStatesStructArray(nRounds, nStops);
        }
    },
    multi_criteria("mc set") {
//        @Override
//        public Worker createWorker(ProfileRequest request, int nRounds, int nStops, RaptorWorkerTransitDataProvider transitData, EgressAccessRouter streetRouter) {
//            McWorkerState state = new McWorkerState(
//                    nRounds,
//                    nStops,
//                    request.maxTripDurationMinutes * 60
//            );
//
//            return new McRangeRaptorWorker(
//                    transitData,
//                    state,
//                    request.fromTime,
//                    request.toTime,
//                    request.walkSpeed,
//                    request.maxWalkTime,
//                    streetRouter.accessTimesToStopsInSeconds,
//                    streetRouter.egressTimesToStopsInSeconds.keys()
//            );
//        }
    }
    ;
    final String name;

    ProfileFactory(String name) {
        this.name = name;
    }

    public static ProfileFactory[] parse(String profiles) {
        return Arrays.stream(profiles.split(",")).map(ProfileFactory::parseOne).toArray(ProfileFactory[]::new);
    }

    private static ProfileFactory parseOne(String value) {
        try {
            return valueOf(value);
        } catch (IllegalArgumentException e) {
            for (ProfileFactory it : values()) {
                if(value == null) { throw e; }
                if(it.name.toLowerCase().startsWith(value)) {
                    return it;
                }
            }
            throw e;
        }
    }

    StopStateCollection createStopStateCollection(int nRounds, int nStops) {
        return new StopStatesIntArray(nRounds, nStops);
    }

    RangeRaptorWorkerState createWorkerState(int nRounds, int nStops, int earliestDepartureTime, int maxDurationSeconds, StopStateCollection stops) {
        return new RangeRaptorWorkerStateImpl(nRounds, nStops, earliestDepartureTime, maxDurationSeconds, stops);
    }

    PathBuilder createPathBuilder(StopStateCollection stops) {
        return new PathBuilderCursorBased(stops.newCursor());
    }

    public Worker createWorker(ProfileRequest request, int nRounds, int nStops, RaptorWorkerTransitDataProvider transitData, EgressAccessRouter streetRouter) {
        StopStateCollection stops = createStopStateCollection(nRounds, nStops);

        RangeRaptorWorkerState state = createWorkerState(
                nRounds,
                nStops,
                request.fromTime,
                request.maxTripDurationMinutes * 60,
                stops
        );

        return new RangeRaptorWorker(
                transitData,
                state,
                createPathBuilder(stops),
                request.fromTime,
                request.toTime,
                request.walkSpeed,
                request.maxWalkTime,
                streetRouter.accessTimesToStopsInSeconds,
                streetRouter.egressTimesToStopsInSeconds.keys()
        );
    }
}
