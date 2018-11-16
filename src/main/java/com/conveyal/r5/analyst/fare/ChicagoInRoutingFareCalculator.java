package com.conveyal.r5.analyst.fare;

import com.conveyal.r5.profile.McRaptorSuboptimalPathProfileRouter;
import com.conveyal.r5.transit.RouteInfo;
import gnu.trove.iterator.TIntIterator;
import gnu.trove.list.TIntList;
import gnu.trove.list.array.TIntArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Greedy fare calculator for the Chicago Transit Authority.
 * Just looks at rail and bus, not at Metra, PACE, etc., and does not handle out-of-system rail transfers.
 */
public class ChicagoInRoutingFareCalculator extends InRoutingFareCalculator {
    public static final int L_FARE = 225;
    public static final int BUS_FARE = 200;
    public static final int TRANSFER_FARE = 25;
    private static final Logger LOG = LoggerFactory.getLogger(ChicagoInRoutingFareCalculator.class);

    @Override
    public FareBounds calculateFare(McRaptorSuboptimalPathProfileRouter.McRaptorState state, int maxClockTime) {
        int fare = 0;

        // extract the relevant rides
        TIntList patterns = new TIntArrayList();

        boolean backL = false;
        int rideCount = 0;

        while (state != null) {
            patterns.add(state.pattern);
            state = state.back;
        }

        List<String> routeNames = new ArrayList();

        patterns.reverse();

        for (TIntIterator patternIt = patterns.iterator(); patternIt.hasNext();) {
            int pattern = patternIt.next();

            if (pattern == -1) {
                // on street transfer, so no free transfer between L lines
                backL = false;
                continue;
            }

            // is this a ride on the L?
            RouteInfo route = transitLayer.routes.get(transitLayer.tripPatterns.get(pattern).routeIndex);
            boolean isL = route.route_type == 1;
            routeNames.add(route.route_short_name != null && !route.route_short_name.isEmpty() ?
                    route.route_short_name : route.route_long_name);

            // every fourth ride you have to pay full fare again
            boolean fullFare = rideCount % 3 == 0;

            if (fullFare) fare += isL ? L_FARE : BUS_FARE;
            else if (!isL || !backL) fare += TRANSFER_FARE;
            // transfers within the L are free

            backL = isL;
            rideCount++;
        }

        // warning: reams of log output
        //  LOG.info("Fare for {}: ${}", String.join(" -> ", routeNames), String.format("%.2f", fare / 100D));

        return new StandardFareBounds(fare);
    }

    @Override
    public String getType() {
        return "chicago";
    }
}
