package com.conveyal.r5.otp2.speed_test.transit;

import com.conveyal.r5.otp2.util.paretoset.ParetoSet;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import static com.conveyal.r5.otp2.speed_test.transit.SpeedTestItinerary.paretoDominanceFunctions;

/**
 * This code is experimental, and just implemented to test if we can get some
 * sense out of a filtered, pareto optimal set of itineraries.
 */
public class ItinerarySet implements Iterable<SpeedTestItinerary> {
    private List<SpeedTestItinerary> itineraries = new ArrayList<>();
    private ParetoSet<SpeedTestItinerary> itinerariesParetoOptimized = new ParetoSet<>(paretoDominanceFunctions());
    private boolean filtered = false;


    public void add(SpeedTestItinerary it) {
        itineraries.add(it);
    }

    void filter() {
        itinerariesParetoOptimized.addAll(itineraries);
        filtered = true;
    }

    @SuppressWarnings("NullableProblems")
    @Override
    public Iterator<SpeedTestItinerary> iterator() {
        return filtered ? itinerariesParetoOptimized.iterator() :  itineraries.iterator();
    }
}
