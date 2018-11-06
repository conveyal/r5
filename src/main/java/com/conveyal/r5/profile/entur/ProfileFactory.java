package com.conveyal.r5.profile.entur;

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

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;


public enum ProfileFactory {
    original("original", "The original code with FastRaptorWorker"),
    int_arrays("int", "Flyweight stop state using int arrays with new RangeRaptorWorker"),
    struct_arrays("struct", "Simple POJO stop arrival state with new RangeRaptorWorker"),
    multi_criteria("mc", "Multi criteria pareto state with new McRangeRaptor")
    ;

    final String shortName;
    final String description;

    ProfileFactory(String shortName, String description) {
        this.shortName = shortName;
        this.description = description;
    }

    public static ProfileFactory[] parse(String profiles) {
        return Arrays.stream(profiles.split(",")).map(ProfileFactory::parseOne).toArray(ProfileFactory[]::new);
    }

    public static List<String> options() {
        return Arrays.stream(values()).map(ProfileFactory::description).collect(Collectors.toList());
    }

    public Worker createWorker(RangeRaptorRequest request, TransitDataProvider transitData, TuningParameters tuningParameters) {
        switch (this) {
            case original:
                throw new IllegalStateException("The original code lives in its original realm...");
            case multi_criteria:
                return createMcRRWorker(tuningParameters, transitData, request);
            case struct_arrays:
            case int_arrays:
                return createRRWorker(tuningParameters, transitData, request);
            default:
                throw new IllegalStateException("Unknown profile: " + this);
        }
    }

    public boolean isOriginal() {
        return is(original);
    }

    public boolean isMultiCriteria() {
        return is(multi_criteria);
    }

    /* private methods */

    private McRangeRaptorWorker createMcRRWorker(TuningParameters tuningParameters, TransitDataProvider transitData, RangeRaptorRequest request) {
        McWorkerState state = new McWorkerState(
                tuningParameters,
                transitData.numberOfStops()
        );

        return new McRangeRaptorWorker(
                transitData,
                state,
                request
        );
    }

    public RangeRaptorWorker createRRWorker(TuningParameters tuningParameters, TransitDataProvider transitData, RangeRaptorRequest request) {

        StopStateCollection stops =
                isStructArrays()
                        ? new StopStatesStructArray(tuningParameters, transitData.numberOfStops())
                        : new StopStatesIntArray(tuningParameters, transitData.numberOfStops());

        return new RangeRaptorWorker(
                tuningParameters,
                transitData,
                stops,
                request
        );
    }

    private boolean is(ProfileFactory other) {
        return this == other;
    }

    private boolean isStructArrays() {
        return is(struct_arrays);
    }

    private static ProfileFactory parseOne(String value) {
        try {
            return valueOf(value);
        } catch (IllegalArgumentException e) {
            for (ProfileFactory it : values()) {
                if(it.shortName.toLowerCase().startsWith(value)) {
                    return it;
                }
            }
            throw e;
        }
    }

    private String description() {
        if(name().equals(shortName)) {
            return String.format("'%s' : %s",   name(), description);
        }
        else {
            return String.format("'%s' or '%s' : %s",   name(), shortName, description);
        }
    }
}
