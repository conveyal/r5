package com.conveyal.r5.analyst.scenario;

import com.conveyal.r5.transit.TransitLayer;
import com.conveyal.r5.transit.TripSchedule;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Multimap;
import com.google.common.primitives.Ints;
import gnu.trove.map.hash.TObjectIntHashMap;
import gnu.trove.set.TIntSet;
import gnu.trove.set.hash.TIntHashSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * Convert scheduled trips to frequencies. Will partition trips by service day.
 */
public class ConvertToFrequency extends TransitLayerModification {

    private static final long serialVersionUID = 1L;

    private static final Logger LOG = LoggerFactory.getLogger(ConvertToFrequency.class);

    public List<TripSchedule> scheduledTrips = new ArrayList<>();
    public List<TripSchedule> frequencyEntries = new ArrayList<>();
    private Multimap<String, TripSchedule> tripsToConvert = HashMultimap.create();

    public String[] routeId;

    @Override public String getType() {
        return "convert-to-frequency";
    }

    /** Windows in which to do the conversion, array of int[2] of startTimeSecs, endTimeSecs */
    public int windowStart;

    public int windowEnd;

    /** How to group trips for conversion to frequencies: by route, route and direction, or by trip pattern. */
    public ConversionGroup groupBy;

    // NO APPLY METHOD, THIS IS A STUB
    @Override
    protected TransitLayer applyToTransitLayer(TransitLayer originalTransitLayer) {
        return null;
    }

    public static enum ConversionGroup {
        ROUTE_DIRECTION, ROUTE, PATTERN;
    }
}
