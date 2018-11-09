package com.conveyal.r5.analyst.fare;

import com.conveyal.gtfs.model.Fare;
import com.conveyal.r5.profile.McRaptorSuboptimalPathProfileRouter;
import com.conveyal.r5.transit.RouteInfo;
import com.conveyal.r5.transit.TransitLayer;
import gnu.trove.list.TIntList;
import gnu.trove.list.array.TIntArrayList;
import org.apache.commons.math3.random.MersenneTwister;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Fare calculator for systems in which:
 * - Each route has a single flat fare
 * - At most one agency provides transfer privileges (pay-the-difference when boarding a more expensive route).
 * - Unlimited transfers may be allowed allowed between routes with certain fares, between stops with zone_id =
 * "station" and sharing the same (non-blank) parent_station
 * This calculator is designed for regions with mixed formal and informal transit services such as Bogota, in which the
 * formal services issue and accept mutually recognized transfer privileges(e.g. between BRT and cheaper zonal/feeder
 * routes) and the semi-formal services (e.g. cash-based private operators) do not.
 * This implementation relies on non-standard conventions being used in input GTFS:
 * - Every route_id is prefixed with "fare_id"+"--"
 * - Stops within the paid area have zone_id = "station"
 */
public class MixedSystemInRoutingFareCalculator extends InRoutingFareCalculator {
    /** If true, log a random 1e-6 sample of fares for spot checking */
    public static final boolean LOG_FARES = false;

    private static final WeakHashMap<TransitLayer, FareSystemWrapper> fareSystemCache = new WeakHashMap<>();
    private Map<String, Fare> fares;
    // Relies on non-standard convention described in class javadoc
    private Map<String, String> fareIdForRouteId;

    // Logging to facilitate debugging
    private static final Logger LOG = LoggerFactory.getLogger(MixedSystemInRoutingFareCalculator.class);

    private MersenneTwister logRandomizer = LOG_FARES ? new MersenneTwister() : null;

    private static int priceToInt(double price) {return (int) (price);} // No conversion for now

    // fares for routes that serve "paid area" stops, at which unlimited transfers may be available
    private static ArrayList<String> faresAvailableInPaidArea = new ArrayList<>(Arrays.asList("T","D"));

    private static String STATION = "station";

    private class MixedSystemTransferAllowance extends TransferAllowance {
        // GTFS-lib does not read fare_attributes.agencyId.  We set it explicitly via routes.agencyId, relying on the
        // convention described above to map routes.route_id to fare_attributes.fare_id.
        private final String agencyId;

        private MixedSystemTransferAllowance () {
            super();
            this.agencyId = null;
        }

        private MixedSystemTransferAllowance (int value, int number, int expirationTime, String agencyId){
            super(value, number, expirationTime);
            this.agencyId = agencyId;
        }

        private MixedSystemTransferAllowance redeemForOneRide(int fareValue) {
            int allowanceValue = Math.max(fareValue, value);
            return new MixedSystemTransferAllowance(allowanceValue, number - 1, expirationTime, agencyId);
        }

    }

    private boolean withinPaidArea(int fromStopIndex, int toStopIndex){
        String fromFareZone = transitLayer.fareZoneForStop.get(fromStopIndex);
        String toFareZone = transitLayer.fareZoneForStop.get(toStopIndex);
        if (STATION.equals(fromFareZone) && STATION.equals(toFareZone)){
            String fromParentStation = transitLayer.parentStationIdForStop.get(fromStopIndex);
            String toParentStation = transitLayer.parentStationIdForStop.get(toStopIndex);
            return fromParentStation != null && fromParentStation.equals(toParentStation);
        } else {
            return false;
        }
    }

    @Override
    public FareBounds calculateFare(McRaptorSuboptimalPathProfileRouter.McRaptorState state, int maxClockTime) {

        // First, load fare data from GTFS
        if (fares == null){
            synchronized (this) {
                if (fares == null){
                    synchronized (fareSystemCache) {
                        FareSystemWrapper fareSystem = fareSystemCache.computeIfAbsent(this.transitLayer,
                                MixedSystemInRoutingFareCalculator::loadFaresFromGTFS);
                        this.fares = fareSystem.fares;
                        this.fareIdForRouteId = fareSystem.fareIdForRouteId;
                    }
                }
            }
        }

        // Initialize: haven't boarded, paid a fare, or received a transfer allowance
        int cumulativeFarePaid = 0;
        MixedSystemTransferAllowance transferAllowance = new MixedSystemTransferAllowance();

        // Extract relevant data about rides
        TIntList patterns = new TIntArrayList();
        TIntList boardStops = new TIntArrayList();
        TIntList alightStops = new TIntArrayList();
        TIntList boardTimes = new TIntArrayList();

        List<String> routeNames;
        if (LOG_FARES) routeNames = new ArrayList<>();

        McRaptorSuboptimalPathProfileRouter.McRaptorState stateForTraversal = state;
        while (stateForTraversal != null) {
            if (stateForTraversal.pattern == -1) {
                stateForTraversal = stateForTraversal.back;
                continue; // on the street, not on transit
            }
            patterns.add(stateForTraversal.pattern);
            alightStops.add(stateForTraversal.stop);
            boardStops.add(transitLayer.tripPatterns.get(stateForTraversal.pattern).stops[stateForTraversal.boardStopPosition]);
            boardTimes.add(stateForTraversal.boardTime);
            stateForTraversal = stateForTraversal.back;
        }

        // reverse data about the rides so we can step forward through them
        patterns.reverse();
        alightStops.reverse();
        boardStops.reverse();
        boardTimes.reverse();

        // Loop over rides to get to the state in forward-chronological order
        for (int ride = 0; ride < patterns.size(); ride ++) {
            int pattern = patterns.get(ride);
            RouteInfo route = transitLayer.routes.get(transitLayer.tripPatterns.get(pattern).routeIndex);
            // used for logging
            if (LOG_FARES) routeNames.add(route.route_short_name != null && !route.route_short_name.isEmpty() ?
                    route.route_short_name : route.route_long_name);

            // board stop for this ride
            int boardStopIndex = boardStops.get(ride);

            // If this is the second ride or later, check whether the route stays within the paid area
            if (ride >= 1) {
                int fromStopIndex = alightStops.get(ride - 1);
                if (withinPaidArea(fromStopIndex, boardStopIndex)) continue;
            }

            int boardClockTime = boardTimes.get(ride);
            String fareId = fareIdForRouteId.get(route.route_id);
            Fare fare = fares.get(fareId);

            // Agency of the board route
            String receivingAgency = route.agency_id;

            // Agency that issued the currently held transfer allowance (possibly several rides ago)
            String issuingAgency = transferAllowance.agencyId;

            // We are not staying within the paid area.  So...
            // Check if enough time has elapsed for our transfer allowance to expire
            if (transferAllowance.hasExpiredAt(boardTimes.get(ride))) transferAllowance = new MixedSystemTransferAllowance();

            // Then check if we might be able to redeem a transfer
            boolean transferValueAvailable =
                    transferAllowance.value > 0 &&
                    transferAllowance.number > 0;

            int undiscountedPrice = priceToInt(fare.fare_attribute.price);

            if (transferValueAvailable) {
                // If transfer value is available, attempt to use it.
                if (receivingAgency.equals(issuingAgency)) {
                    // Pay difference and set updated transfer allowance
                    cumulativeFarePaid += transferAllowance.payDifference(undiscountedPrice);
                    transferAllowance = transferAllowance.redeemForOneRide(undiscountedPrice);
                } else {
                    // This agency will not accept currently held transfer allowance.  Hold onto it, and pay full fare.
                    cumulativeFarePaid += undiscountedPrice;
                }
            } else {
                // Pay full fare and obtain new transfer allowance
                cumulativeFarePaid += undiscountedPrice;
                transferAllowance = new MixedSystemTransferAllowance(priceToInt(fare.fare_attribute.price),
                        fare.fare_attribute.transfers,boardClockTime + fare.fare_attribute.transfer_duration,
                        receivingAgency);
            }
        }

        // warning: reams of log output
        // only log 1/1000000 of the fares
        if (LOG_FARES && logRandomizer.nextInt(1000000) == 42) {
            LOG.info("Fare for {}: ${}", String.join(" -> ", routeNames), String.format("%.2f", cumulativeFarePaid / 100D));
        }

        return new FareBounds(cumulativeFarePaid, transferAllowance.tightenExpiration(maxClockTime));
    }

    @Override
    public String getType() {
        return "mixed-system";
    }

    private static class FareSystemWrapper{
        public Map<String, Fare> fares;
        public Map<String, String> fareIdForRouteId;

        private FareSystemWrapper(Map<String, Fare> fares, Map<String, String> fareIdForRouteId) {
            this.fares = fares;
            this.fareIdForRouteId = fareIdForRouteId;
        }
    }

    private static FareSystemWrapper loadFaresFromGTFS(TransitLayer transitLayer){
        Map<String, Fare> fares = new HashMap<>();
        Map<String, String> fareIdForRouteId = new HashMap<>();

        // iterate through fares to record rules
        for (Fare fare : transitLayer.fares.values()){
            fares.putIfAbsent(fare.fare_id, fare);
        }

        for (RouteInfo route : transitLayer.routes){
            fareIdForRouteId.put(route.route_id, route.route_id.split("--")[0]);
        }

        return new FareSystemWrapper(fares, fareIdForRouteId);
    }
}