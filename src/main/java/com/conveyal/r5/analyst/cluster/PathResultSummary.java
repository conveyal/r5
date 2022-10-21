package com.conveyal.r5.analyst.cluster;

import com.conveyal.r5.analyst.StreetTimesAndModes;
import com.conveyal.r5.transit.TransitLayer;
import com.conveyal.r5.transit.path.StopSequence;
import gnu.trove.list.TIntList;
import gnu.trove.list.array.TIntArrayList;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Convert a PathResult to an API friendly version. Uses the transit layer to get route and stop details.
 */
public class PathResultSummary {
    public List<IterationDetails> iterations = new ArrayList<>();
    public List<Itinerary> itineraries = new ArrayList<>();
    public int fastestPathTime = Integer.MAX_VALUE;

    public PathResultSummary(
            PathResult pathResult,
            TransitLayer transitLayer
    ) {
        if (pathResult == null || pathResult.iterationsForPathTemplates.length != 1 || pathResult.iterationsForPathTemplates[0] == null)
            return;

        // Iterate through each path result creating a list of iteration details and itineraries that reference each
        // other through an index.
        int itineraryIndex = 0;
        for (var pathTemplate : pathResult.iterationsForPathTemplates[0].keySet()) {
            var allIterations = pathResult.iterationsForPathTemplates[0].get(pathTemplate);
            TIntArrayList durations = new TIntArrayList();
            int fastestIteration = Integer.MAX_VALUE;
            for (var iteration : allIterations) {
                if (iteration.totalTime < fastestIteration) {
                    fastestIteration = iteration.totalTime;
                    if (fastestIteration < fastestPathTime) fastestPathTime = fastestIteration;
                }
                // Add to the set of durations
                durations.add(iteration.totalTime);
                IterationDetails iterationDetails = new IterationDetails(
                        iteration.departureTime,
                        iteration.waitTimes,
                        iteration.totalTime,
                        itineraryIndex
                );
                // Add to the list of departure times
                this.iterations.add(iterationDetails);
            }
            List<TransitLeg> transitLegs = new ArrayList<>();
            for (int routeIndex = 0; routeIndex < pathTemplate.routes.size(); routeIndex++) {
                transitLegs.add(new TransitLeg(transitLayer, pathTemplate.stopSequence, routeIndex));
            }
            itineraries.add(new Itinerary(
                    pathTemplate.stopSequence.access,
                    pathTemplate.stopSequence.egress,
                    transitLegs,
                    allIterations.size(),
                    durations.min(),
                    durations.max(),
                    itineraryIndex
            ));
            itineraryIndex += 1;
        }
        // Sort the iterations (by departure time and itinerary index)
        Collections.sort(iterations);
    }

    /**
     * An itinerary taken from a path result, including access and egress mode, transit legs, duration range, and how
     * many iterations this itinerary was used.
     */
    static class Itinerary {
        public StreetTimesAndModes.StreetTimeAndMode access;
        public StreetTimesAndModes.StreetTimeAndMode egress;
        public List<TransitLeg> transitLegs;
        public int iterationsOptimal;
        public int minDuration;
        public int maxDuration;

        public int index;

        Itinerary(
                StreetTimesAndModes.StreetTimeAndMode access,
                StreetTimesAndModes.StreetTimeAndMode egress,
                List<TransitLeg> transitLegs,
                int iterationsOptimal,
                int minDuration,
                int maxDuration,
                int index
        ) {
            this.access = access;
            this.egress = egress;
            this.transitLegs = transitLegs;
            this.iterationsOptimal = iterationsOptimal;
            this.maxDuration = maxDuration;
            this.minDuration = minDuration;
            this.index = index;
        }
    }

    /**
     * String representations of the boarding stop, alighting stop, and route, with in-vehicle time.
     */
    static class TransitLeg {
        public String routeId;
        public String routeName;
        public int inVehicleTime;
        public String boardStopId;
        public String boardStopName;

        public String alightStopId;
        public String alightStopName;

        public TransitLeg(
                TransitLayer transitLayer,
                StopSequence stopSequence,
                int routeIndex
        ) {
            var routeInfo = transitLayer.routes.get(routeIndex);
            routeId = routeInfo.route_id;
            routeName = routeInfo.getName();

            inVehicleTime = stopSequence.rideTimesSeconds.get(routeIndex);

            int boardStopIndex = stopSequence.boardStops.get(routeIndex);
            boardStopId = getStopId(transitLayer, boardStopIndex);
            boardStopName = transitLayer.stopNames.get(boardStopIndex);

            int alightStopIndex = stopSequence.alightStops.get(routeIndex);
            alightStopId = getStopId(transitLayer, alightStopIndex);
            alightStopName = transitLayer.stopNames.get(alightStopIndex);
        }
    }

    /**
     * Temporal details of a specific iteration of our RAPTOR implementation (per-leg wait times and total time
     * implied by a specific departure time and randomized schedule offsets).
     */
    public static class IterationDetails implements Comparable<IterationDetails> {
        public final int departureTime;
        public final int[] waitTimes;
        public final int totalWaitTime;
        public final int totalTime;
        public final int itineraryIndex;

        public IterationDetails(int departureTime, TIntList waitTimes, int totalTime, int itineraryIndex) {
            this.departureTime = departureTime;
            this.waitTimes = waitTimes.toArray();
            this.totalTime = totalTime;
            this.totalWaitTime = waitTimes.sum();
            this.itineraryIndex = itineraryIndex;
        }

        @Override
        public int compareTo(IterationDetails o) {
            int diff = this.departureTime - o.departureTime;
            if (diff != 0) return diff;
            return this.itineraryIndex - o.itineraryIndex;
        }
    }

    /**
     * Get the stop ID. If it does not exist, it is a new stop and return "new".
     *
     * @param stopIndex
     * @return stopId
     */
    private static String getStopId(TransitLayer transitLayer, int stopIndex) {
        String stopId = transitLayer.stopIdForIndex.get(stopIndex);
        if (stopId == null) return "[new]";
        return stopId;
    }
}