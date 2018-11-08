package com.conveyal.r5.profile.entur;

import com.conveyal.r5.profile.entur.api.Path2;
import com.conveyal.r5.profile.entur.api.RaptorProfiles;
import com.conveyal.r5.profile.entur.api.RangeRaptorRequest;
import com.conveyal.r5.profile.entur.api.TripScheduleInfo;
import com.conveyal.r5.profile.entur.api.TuningParameters;
import com.conveyal.r5.profile.entur.rangeraptor.Worker;
import com.conveyal.r5.profile.entur.rangeraptor.standard.RangeRaptorWorker;
import com.conveyal.r5.profile.entur.api.TransitDataProvider;
import com.conveyal.r5.profile.entur.rangeraptor.standard.StopArrivalCollection;
import com.conveyal.r5.profile.entur.rangeraptor.standard.intarray.StopIntArray;
import com.conveyal.r5.profile.entur.rangeraptor.standard.structarray.StopStructArray;
import com.conveyal.r5.profile.entur.rangeraptor.multicriteria.McRangeRaptorWorker;

import java.util.Collection;

/**
 * A service for performing Range Raptor routing request.
 */
public class RangeRaptorService<T extends TripScheduleInfo> {
    private TuningParameters tuningParameters;

    public RangeRaptorService(TuningParameters tuningParameters) {
        this.tuningParameters = tuningParameters;
    }

    public Collection<Path2<T>> route(RangeRaptorRequest request, TransitDataProvider<T> transitData) {
        Worker<T> worker = createWorker(request, transitData);
        return worker.route();
    }


    /* private methods */

    private Worker<T> createWorker(RangeRaptorRequest request, TransitDataProvider<T> transitData) {
        switch (request.profile) {
            case MULTI_CRITERIA:
                return createMcRRWorker(transitData, request);
            case STRUCT_ARRAYS:
            case INT_ARRAYS:
                return createRRWorker(transitData, request);
            default:
                throw new IllegalStateException("Unknown profile: " + this);
        }
    }

    private Worker<T> createMcRRWorker(TransitDataProvider<T> transitData, RangeRaptorRequest request) {
        int nRounds = nRounds(tuningParameters);
        return new McRangeRaptorWorker<>(transitData, request, nRounds);
    }

    private Worker<T> createRRWorker(TransitDataProvider<T> transitData, RangeRaptorRequest request) {
        int nRounds = nRounds(tuningParameters);

        StopArrivalCollection<T> stops =
                request.profile == RaptorProfiles.STRUCT_ARRAYS
                        ? new StopStructArray(nRounds, transitData.numberOfStops())
                        : new StopIntArray(nRounds, transitData.numberOfStops());

        return new RangeRaptorWorker<T>(
                transitData,
                nRounds,
                stops,
                request
        );
    }

    /** Calculate the maximum number of rounds to perform. */
    private int nRounds(TuningParameters tuningParameters) {
        return tuningParameters.maxNumberOfTransfers() + 1;
    }
}
