package com.conveyal.r5.speed_test;

import com.conveyal.r5.profile.ProfileRequest;
import com.conveyal.r5.profile.StreetPath;
import com.conveyal.r5.streets.StreetRouter;
import com.conveyal.r5.transit.TransportNetwork;
import com.conveyal.r5.util.AvgTimer;
import gnu.trove.map.TIntIntMap;

public class EgressAccessRouter {

    private final static AvgTimer TIMER = AvgTimer.timerMilliSec("EgressAccessRouter:route");
    private final TransportNetwork transportNetwork;
    private final ProfileRequest request;

    StreetRouter egressRouter;
    StreetRouter accessRouter;

    TIntIntMap egressTimesToStopsInSeconds;
    TIntIntMap accessTimesToStopsInSeconds;

    EgressAccessRouter(TransportNetwork transportNetwork, ProfileRequest request) {
        this.transportNetwork = transportNetwork;
        this.request = request;
    }

    void route() {
        TIMER.time(() -> {
            egressRouter = streetRoute(request, true);
            accessRouter = streetRoute(request, false);

            egressTimesToStopsInSeconds = egressRouter.getReachedStops();
            accessTimesToStopsInSeconds = accessRouter.getReachedStops();
        });
    }

    StreetPath accessPath(int boardStopIndex) {
        StreetRouter.State accessState = accessRouter.getStateAtVertex(
                transportNetwork.transitLayer.streetVertexForStop.get(boardStopIndex)
        );
        return new StreetPath(accessState, transportNetwork, false);
    }

    StreetPath egressPath(int alightStopIndex) {
        StreetRouter.State egressState = egressRouter.getStateAtVertex(
                transportNetwork.transitLayer.streetVertexForStop.get(alightStopIndex)
        );
        return new StreetPath(egressState, transportNetwork, false);
    }

    private StreetRouter streetRoute(ProfileRequest request, boolean fromDest) {
        // Search for access to / egress from transit on streets.
        StreetRouter sr = new StreetRouter(transportNetwork.streetLayer);
        sr.profileRequest = request;
        if (!fromDest ? !sr.setOrigin(request.fromLat, request.fromLon) : !sr.setOrigin(request.toLat, request.toLon)) {
            throw new RuntimeException("Point not near a road.");
        }
        sr.timeLimitSeconds = request.maxWalkTime * 60;
        sr.quantityToMinimize = StreetRouter.State.RoutingVariable.DURATION_SECONDS;
        sr.route();
        return sr;
    }

}
