package com.conveyal.r5.profile.entur;

import com.conveyal.r5.profile.entur.api.Path2;
import com.conveyal.r5.profile.entur.api.RaptorProfiles;
import com.conveyal.r5.profile.entur.api.RangeRaptorRequest;
import com.conveyal.r5.profile.entur.api.TuningParameters;
import com.conveyal.r5.profile.entur.rangeraptor.standard.RangeRaptorWorker;
import com.conveyal.r5.profile.entur.api.TransitDataProvider;
import com.conveyal.r5.profile.entur.rangeraptor.standard.StopStateCollection;
import com.conveyal.r5.profile.entur.rangeraptor.standard.intarray.StopStatesIntArray;
import com.conveyal.r5.profile.entur.rangeraptor.standard.structarray.StopStatesStructArray;
import com.conveyal.r5.profile.entur.api.Worker;
import com.conveyal.r5.profile.entur.rangeraptor.multicriteria.McRangeRaptorWorker;
import com.conveyal.r5.profile.entur.rangeraptor.multicriteria.McWorkerState;

import java.util.Collection;

/**
 * A service for performing Range Raptor routing request.
 */
public class RangeRaptorService {
    private TuningParameters tuningParameters;

    public RangeRaptorService(TuningParameters tuningParameters) {
        this.tuningParameters = tuningParameters;
    }

    public Collection<Path2> route(RangeRaptorRequest request, TransitDataProvider transitData) {
        Worker worker = createWorker(request, transitData);
        return worker.route();
    }


    /* private methods */

    private Worker createWorker(RangeRaptorRequest request, TransitDataProvider transitData) {
        switch (request.profile) {
            case MULTI_CRITERIA:
                return createMcRRWorker(tuningParameters, transitData, request);
            case STRUCT_ARRAYS:
            case INT_ARRAYS:
                return createRRWorker(tuningParameters, transitData, request);
            default:
                throw new IllegalStateException("Unknown profile: " + this);
        }
    }

    private McRangeRaptorWorker createMcRRWorker(TuningParameters tuningParameters, TransitDataProvider transitData, RangeRaptorRequest request) {
        int nRounds = nRounds(tuningParameters);

        McWorkerState state = new McWorkerState(
                nRounds,
                transitData.numberOfStops()
        );

        return new McRangeRaptorWorker(
                transitData,
                state,
                request
        );
    }

    private RangeRaptorWorker createRRWorker(TuningParameters tuningParameters, TransitDataProvider transitData, RangeRaptorRequest request) {
        int nRounds = nRounds(tuningParameters);

        StopStateCollection stops =
                request.profile == RaptorProfiles.STRUCT_ARRAYS
                        ? new StopStatesStructArray(nRounds, transitData.numberOfStops())
                        : new StopStatesIntArray(nRounds, transitData.numberOfStops());

        return new RangeRaptorWorker(
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
