package com.conveyal.r5.speed_test;

import com.conveyal.r5.profile.ProfileRequest;
import com.conveyal.r5.profile.SearchAlgorithm;
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


enum ProfileFactory {
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

    static ProfileFactory[] parse(String profiles) {
        return Arrays.stream(profiles.split(",")).map(ProfileFactory::parseOne).toArray(ProfileFactory[]::new);
    }

    static ProfileFactory from(SearchAlgorithm algorithm, ProfileFactory defaultFactory) {
        if(algorithm == null) return defaultFactory;

        switch (algorithm) {
            case RangeRaptor: return original;
            case MultiCriteriaRangeRaptor: return multi_criteria;
            case StructRangeRaptor: return struct_arrays;
            case IntArrayRangeRaptor: return int_arrays;
        }
        return defaultFactory;
    }

    public static List<String> options() {
        return Arrays.stream(values()).map(ProfileFactory::description).collect(Collectors.toList());
    }

    public Worker createWorker(ProfileRequest request, int nRounds, TransitDataProvider transitData) {
        if(isMultiCriteria()) {
            return createMcWorker(request, nRounds, transitData);
        }
        if(isOriginal()) {
            throw new IllegalStateException("The original code lives in its original realm...");
        }

        StopStateCollection stops =
                isStructArrays()
                        ? new StopStatesStructArray(nRounds, transitData.numberOfStops())
                        : new StopStatesIntArray(nRounds, transitData.numberOfStops());

        return new RangeRaptorWorker(
                nRounds,
                transitData.numberOfStops(),
                request.maxTripDurationMinutes * 60,
                transitData,
                stops
        );
    }

    private McRangeRaptorWorker createMcWorker(ProfileRequest request, int nRounds, TransitDataProvider transitData) {
        McWorkerState state = new McWorkerState(
                nRounds,
                transitData.numberOfStops(),
                request.maxTripDurationMinutes * 60
        );

        return new McRangeRaptorWorker(
                transitData,
                state
        );
    }

    private boolean is(ProfileFactory other) {
        return this == other;
    }

    boolean isOriginal() {
        return is(original);
    }

    boolean isMultiCriteria() {
        return is(multi_criteria);
    }

    private boolean isStructArrays() {
        return is(struct_arrays);
    }

    /* private methods */

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
