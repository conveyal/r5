package com.conveyal.r5.analyst;

import com.conveyal.gtfs.flex.OnDemand;
import com.conveyal.gtfs.flex.OnDemandPlaceFilter;
import com.conveyal.r5.profile.ProfileRequest;
import com.conveyal.r5.profile.StreetMode;
import com.conveyal.r5.streets.LinkedPointSet;
import com.conveyal.r5.streets.PointSetTimes;
import com.conveyal.r5.streets.StreetRouter;
import com.conveyal.r5.transit.TransportNetwork;

import java.util.List;

import static com.conveyal.r5.profile.PerTargetPropagater.MM_PER_METER;

/// An instance of this class holds the multi-valued return of its static [#route] method. That
/// method handles routing for a collection of OnDemand services that may be available in the
/// area reached by a completed access search (usually walking or biking), as well as walking after
/// those on-demand rides. Both TravelTimeComputer and street routing tests call [#route], ensuring
/// tests follow exactly the routing process used in production.
public class OnDemandAccess {

    /// Performs a walk search onward from the clipped final states of all candidate services.
    /// Transit stop arrival times for on-demand access are read from this router.
    public final StreetRouter egressRouter;

    /// Direct on-demand travel times to the supplied destination points, which are the cell-wise
    /// minimum of the car and walk searches (for on-demand and the following walk), or null when
    /// no destinations were supplied. This does not include times using the pre-on-demand access
    /// mode alone, which the caller determines using its own access router.
    public final PointSetTimes directTimes;

    private OnDemandAccess (StreetRouter egressRouter, PointSetTimes directTimes) {
        this.egressRouter = egressRouter;
        this.directTimes = directTimes;
    }

    /// Runs the complete on-demand portion of routing, for access to scheduled transit or direct
    /// access to destinations.
    ///
    /// 1. Per candidate service, a car search is run from all access states accepted by the pick-up
    ///    place filter, as well as the origin point, and results clipped to its drop-off place filter.
    /// 2. Clipped results for each service are merged into one temporary router without ever modifying
    ///    the supplied access router. Walking states do not evict ride states or vice versa.
    /// 3. A single walk search is initialized from those states. Its results feed every downstream
    ///    stage: flex drop-off stops, scheduled transit stops reached by transfers, streets reached
    ///    by walking including pedestrian-only areas.
    /// 4. When a destination PointSet is supplied, travel times are propagated to them as the
    ///    minimum of a) the car linkage clipped by the destination drop-off place and b) the WALK
    ///    linkage with no such filtering.
    ///
    /// The car search must be run separately per service as different destination filtering must be
    /// applied to their results separately.
    ///
    /// The supplied OnDemand services should be pre-filtered for plausible spatial and temporal
    /// overlap with the access search, but they undergo final selection here using place filters
    /// and boarding time windows. The midTime is a representative departure time from which all
    /// availability is evaluated. Destinations may be null when only street and transit stop times
    /// are needed.
    public static OnDemandAccess route (
                StreetRouter accessRouter,
                List<OnDemand> candidates,
                int midTime,
                PointSet destinations
            ) {
        TransportNetwork network = accessRouter.streetLayer.parentNetwork;
        ProfileRequest request = accessRouter.profileRequest;
        LinkedPointSet walkLinkage = null;
        LinkedPointSet carLinkage = null;
        int walkSpeedMmPerSecond = 0;
        int carSpeedMmPerSecond = 0;
        if (destinations != null) {
            walkLinkage = network.linkageCache.getLinkage(destinations, network.streetLayer, StreetMode.WALK);
            carLinkage = network.linkageCache.getLinkage(destinations, network.streetLayer, StreetMode.CAR);
            walkSpeedMmPerSecond = (int) (request.walkSpeed * MM_PER_METER);
            // The car linkage replaces this speed with each destination edge's own car speed.
            carSpeedMmPerSecond = (int) (request.getSpeedForMode(StreetMode.CAR) * MM_PER_METER);
        }
        // Merged results accumulate in a temporary router so each service's sub-search becomes
        // garbage before the next one is initialized. Initial states are always taken from the
        // access search, preventing chained on-demand rides.
        StreetRouter rides = accessRouter.shallowCopyForRouting();
        PointSetTimes carArmTimes = null;
        for (OnDemand od : candidates) {
            OnDemandPlaceFilter dropOffPlace = OnDemandPlaceFilter.dropOff(od, network);
            StreetRouter ride = accessRouter.copyAndRouteFor(od, midTime);
            ride.clipStates(dropOffPlace);
            if (carLinkage != null) {
                PointSetTimes delivered = carLinkage.evalClipped(
                        ride::getTravelTimeToVertex, carSpeedMmPerSecond, walkSpeedMmPerSecond, dropOffPlace);
                carArmTimes = PointSetTimes.minMerge(carArmTimes, delivered);
            }
            rides.mergeStatesFrom(ride);
        }
        StreetRouter egressRouter = rides.copyAndRouteEgressWalk();
        PointSetTimes directTimes = carArmTimes;
        if (walkLinkage != null) {
            PointSetTimes walked = walkLinkage.eval(
                    egressRouter::getTravelTimeToVertex, walkSpeedMmPerSecond, walkSpeedMmPerSecond, null);
            directTimes = PointSetTimes.minMerge(directTimes, walked);
        }
        return new OnDemandAccess(egressRouter, directTimes);
    }

}
