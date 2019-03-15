package com.conveyal.r5.speed_test;

import com.conveyal.r5.profile.FastRaptorWorker;
import com.conveyal.r5.profile.ProfileRequest;
import com.conveyal.r5.profile.entur.util.AvgTimer;
import com.conveyal.r5.speed_test.api.model.Itinerary;
import com.conveyal.r5.speed_test.api.model.TripPlan;
import com.conveyal.r5.speed_test.transit.EgressAccessRouter;
import com.conveyal.r5.speed_test.transit.ItineraryMapper;
import com.conveyal.r5.speed_test.transit.TripPlanSupport;
import com.conveyal.r5.transit.TransportNetwork;
import gnu.trove.iterator.TIntIntIterator;

import java.util.Calendar;

import static com.conveyal.r5.profile.entur.util.TimeUtils.midnightOf;

class RunOriginalWorker {
    /**
     * RUN ORIGINAL CODE
     */
    static TripPlan route(ProfileRequest request, TransportNetwork transportNetwork, AvgTimer timerWorker) {
        try {
            TripPlan tripPlan = TripPlanSupport.createTripPlanForRequest(request);

            for (int i = 0; i < request.numberOfItineraries; i++) {
                EgressAccessRouter streetRouter = new EgressAccessRouter(transportNetwork, request);
                streetRouter.route();

                FastRaptorWorker worker = new FastRaptorWorker(transportNetwork.transitLayer, request, streetRouter.accessTimesToStopsInSeconds);
                worker.retainPaths = true;


                timerWorker.start();
                // Run the main RAPTOR algorithm to find paths and travel times to all stops in the network.
                // Returns the total travel times as a 2D array of [searchIteration][destinationStopIndex].
                // Additional detailed path information is retained in the FastRaptorWorker after routing.
                int[][] transitTravelTimesToStops = worker.route();
                timerWorker.stop();

                int bestKnownTime = Integer.MAX_VALUE; // Hack to bypass Java stupid "effectively final" requirement.
                com.conveyal.r5.profile.Path bestKnownPath = null;
                TIntIntIterator egressTimeIterator = streetRouter.egressTimesToStopsInSeconds.iterator();
                int egressTime = 0;
                int accessTime = 0;
                while (egressTimeIterator.hasNext()) {
                    egressTimeIterator.advance();
                    int stopIndex = egressTimeIterator.key();
                    egressTime = egressTimeIterator.value();
                    int travelTimeToStop = transitTravelTimesToStops[0][stopIndex];
                    if (travelTimeToStop != FastRaptorWorker.UNREACHED) {
                        int totalTime = travelTimeToStop + egressTime;
                        if (totalTime < bestKnownTime) {
                            bestKnownTime = totalTime;
                            bestKnownPath = worker.pathsPerIteration.get(0)[stopIndex];
                            accessTime = streetRouter.accessTimesToStopsInSeconds.get(worker.pathsPerIteration.get(0)[stopIndex].boardStops[0]);
                        }
                    }
                }

                if (bestKnownPath == null) {
                    System.err.printf("\n\n!!! NO RESULT FOR INPUT. From ({}, {}) to ({}, {}).\n", request.fromLat, request.fromLon, request.toLat, request.toLon);
                    break;
                }

                Itinerary itinerary = ItineraryMapper.createItinerary(transportNetwork, request, streetRouter, bestKnownPath);

                if (itinerary != null) {
                    tripPlan.addItinerary(itinerary);
                    Calendar fromMidnight = midnightOf(itinerary.startTime);
                    request.fromTime = (int) (itinerary.startTime.getTimeInMillis() - fromMidnight.getTimeInMillis()) / 1000 + 60;
                    request.toTime = request.fromTime + 60;
                } else {
                    break;
                }
            }
            return tripPlan;
        }
        finally {
            timerWorker.failIfStarted();
        }
    }
}
