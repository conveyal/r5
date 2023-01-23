package com.conveyal.r5.analyst.cluster;

import com.conveyal.r5.analyst.StreetTimeAndMode;
import com.conveyal.r5.transit.TransitLayer;
import com.conveyal.r5.transit.path.StopSequence;
import com.google.common.base.Preconditions;
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
    public int fastestPathSeconds = Integer.MAX_VALUE;

    public static PathResultSummary createSummary(
            PathResult[] pathResults,
            TransitLayer transitLayer
    ) {
        PathResultSummary pathResultSummary = null;
        if (pathResults != null && pathResults.length > 0) {
            Preconditions.checkState(pathResults.length == 1, "Paths were stored for multiple " +
                    "destinations, but only one is being requested");
            if (pathResults[0] != null) {
                pathResultSummary = new PathResultSummary(
                        pathResults[0],
                        transitLayer
                );
            }
        }
        return pathResultSummary;
    }

    public PathResultSummary(
            PathResult pathResult,
            TransitLayer transitLayer
    ) {
        // Iterate through each path result creating a list of iteration details and itineraries that reference each
        // other through an index.
        int itineraryIndex = 0;
        for (var pathTemplate : pathResult.getAllPathTemplates()) {
            var allIterations = pathResult.getIterationsForPathTemplate(pathTemplate);
            TIntArrayList durations = new TIntArrayList();
            int fastestIteration = Integer.MAX_VALUE;
            for (var iteration : allIterations) {
                if (iteration.totalTime < fastestIteration) {
                    fastestIteration = iteration.totalTime;
                    if (fastestIteration < fastestPathSeconds) fastestPathSeconds = fastestIteration;
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
            for (int routeIndex = 0; routeIndex < pathTemplate.routeIndexes.size(); routeIndex++) {
                transitLegs.add(new TransitLeg(transitLayer, pathTemplate.stopSequence, routeIndex));
            }
            itineraries.add(new Itinerary(
                    pathTemplate.access,
                    pathTemplate.egress,
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
     * An itinerary taken from a path result, including access and egress mode, transit legs, duration range (seconds),
     * and how many iterations this itinerary was used.
     */
    static class Itinerary {
        public StreetTimeAndMode access;
        public StreetTimeAndMode egress;
        public List<TransitLeg> transitLegs;
        public int iterationsOptimal;
        public int minSeconds;
        public int maxSeconds;

        public int index;

        Itinerary(
                StreetTimeAndMode access,
                StreetTimeAndMode egress,
                List<TransitLeg> transitLegs,
                int iterationsOptimal,
                int minSeconds,
                int maxSeconds,
                int index
        ) {
            this.access = access;
            this.egress = egress;
            this.transitLegs = transitLegs;
            this.iterationsOptimal = iterationsOptimal;
            this.maxSeconds = maxSeconds;
            this.minSeconds = minSeconds;
            this.index = index;
        }
    }

    /**
     * String representations of the boarding stop, alighting stop, and route, with ride time (in seconds).
     */
    static class TransitLeg {
        public String routeId;
        public String routeName;
        public int rideTimeSeconds;
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

            rideTimeSeconds = stopSequence.rideTimesSeconds.get(routeIndex);

            int boardStopIndex = stopSequence.boardStops.get(routeIndex);
            boardStopId = transitLayer.getStopId(boardStopIndex);
            boardStopName = transitLayer.stopNames.get(boardStopIndex);

            int alightStopIndex = stopSequence.alightStops.get(routeIndex);
            alightStopId = transitLayer.getStopId(alightStopIndex);
            alightStopName = transitLayer.stopNames.get(alightStopIndex);
        }
    }

    /**
     * Temporal details of a specific iteration of our RAPTOR implementation (per-leg wait times and total time
     * implied by a specific departure time and randomized schedule offsets).
     * <p>
     * All times are in seconds. Departure time is seconds from midnight.
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
}