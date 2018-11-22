package com.conveyal.r5.analyst.fare;

import com.conveyal.r5.profile.McRaptorSuboptimalPathProfileRouter;
import com.conveyal.r5.transit.RouteInfo;
import gnu.trove.iterator.TIntIterator;
import gnu.trove.list.TIntList;
import gnu.trove.list.array.TIntArrayList;

/**
 * Calculate fares in Bogotá, Colombia.
 */
public class BogotaInRoutingFareCalculator extends InRoutingFareCalculator {
    // base fares, all in Colombian pesos
    /** Fare to ride TPC (local service) */
    public int tpcBaseFare = 0;

    /** Fare to ride TransMilenio */
    public int tmBaseFare = 0;

    // transfer fares

    /** fare when boarding TransMilenio after leaving TPC */
    public int tpcToTmFare = 0;

    /** fare when boarding TPC after leaving TPC */
    public int tpcToTpcFare = 0;

    /** fare when boarding TPC after leaving TransMilenio */
    public int tmToTpcFare = 0;

    /** fare when transferring between TransMilenio lines (TransMilenio has free transfers) */
    public int tmToTmFare = 0;

    public String tpcAgencyName;

    public String tmAgencyName;

    // There is some additional complexity which we're not representing here.
    // There is a maximum of four transfers but we're limiting the analysis to four rides, so that doesn't apply
    // There is also a maximum transfer window of 75 minutes but our analysis window is 60 minutes so it's non-binding

    @Override
    public FareBounds calculateFare(McRaptorSuboptimalPathProfileRouter.McRaptorState state, int maxClockTime) {
        int fare = 0;

        // extract the relevant rides
        TIntList patterns = new TIntArrayList();

        while (state != null) {
            if (state.pattern > -1) patterns.add(state.pattern);
            state = state.back;
        }

        patterns.reverse();

        RouteType prevRouteType = null;

        for (TIntIterator patternIt = patterns.iterator(); patternIt.hasNext();) {
            int pattern = patternIt.next();

            RouteInfo ri = transitLayer.routes.get(transitLayer.tripPatterns.get(pattern).routeIndex);

            RouteType routeType = RouteType.fromAgencyName(ri.agency_name, this);

            if (prevRouteType == null) {
                // not a transfer
                if (routeType == RouteType.TPC) fare += tpcBaseFare;
                else fare += tmBaseFare;
            } else {
                // NB this is only considering the previous ride. A clever traveler might keep separate tickets for their
                // TPC and TransMilenio trips in order to take advantage of the transfer rules (I haven't evaluated if this
                // could save you anything, but I can imagine a fare system where it would).
                if (prevRouteType == RouteType.TPC && routeType == RouteType.TPC) fare += tpcToTpcFare;
                else if (prevRouteType == RouteType.TPC && routeType == RouteType.TRANSMILENIO) fare += tpcToTmFare;
                else if (prevRouteType == RouteType.TRANSMILENIO && routeType == RouteType.TPC) fare += tmToTpcFare;
                else fare += tmToTmFare;
            }

            prevRouteType = routeType;
        }

        return new StandardFareBounds(fare);
    }

    @Override
    public String getType() {
        return "bogota";
    }

    private enum RouteType {
        TPC, TRANSMILENIO;

        public static RouteType fromAgencyName (String agencyName, BogotaInRoutingFareCalculator calculator) {
            if (calculator.tmAgencyName.equals(agencyName)) {
                return TRANSMILENIO;
            } else {
                return TPC;
            }
        }
    }
}
