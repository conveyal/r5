package com.conveyal.r5.speed_test;

import com.conveyal.r5.profile.mcrr.util.ParetoSet;

import java.util.ArrayList;
import java.util.List;

import static com.conveyal.r5.speed_test.SpeedTestItinerary.paretoDominanceFunctions;

/**
 * This code is experimental, and just implemented to test if we can get some
 * sense out of a filtered, pareto optimal set of itineraries.
 */
public class ItinerarySet {
    private List<SpeedTestItinerary> itineraries = new ArrayList<>();
    private ParetoSet<SpeedTestItinerary> itinerariesPOptimized = new ParetoSet<>(paretoDominanceFunctions());


    void add(SpeedTestItinerary it) {
        itineraries.add(it);
    }

    void filter() {
        itineraries.forEach(itinerariesPOptimized::add);
    }

    public Iterable<SpeedTestItinerary> iterator() {
        return itinerariesPOptimized.paretoSet();
    }
}
