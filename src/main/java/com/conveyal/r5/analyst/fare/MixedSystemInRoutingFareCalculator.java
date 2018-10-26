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
 * Fare calculator for systems with a single fare per route, in which at most one agency provides transfer privileges
 * (pay-the-difference when boarding a more expensive route).
 * This calculator is appropriate for regions with mixed formal and informal transit services, in which the formal
 * services issue and accept mutually recognized transfer privileges(e.g. between BRT and cheaper zonal/feeder
 * routes) and the semi-formal services (e.g. cash-based private operators) do not.
 * This implementation relies on a non-standard convention for route_id and fare_id values in GTFS: routeIds are
 * prefixed with "fareId"+"--"
 */
public class MixedSystemInRoutingFareCalculator extends InRoutingFareCalculator {
    /** If true, log a random 1e-6 sample of fares for spot checking */
    public static final boolean LOG_FARES = false;

    private static final WeakHashMap<TransitLayer, FareSystemWrapper> fareSystemCache = new WeakHashMap<>();
    private Map<String, Fare> fares;

    // Logging to facilitate debugging
    private static final Logger LOG = LoggerFactory.getLogger(MixedSystemInRoutingFareCalculator.class);

    private MersenneTwister logRandomizer = LOG_FARES ? new MersenneTwister() : null;

    private static int priceToInt(double price) {return (int) (price);} // No conversion for now

    // Relies on non-standard convention described in class javadoc
    private static String getFareIdFromRouteId(RouteInfo route) {return route.route_id.split("--")[0];}

    // TODO read from GTFS
    private static ArrayList<String> faresAvailableInPaidArea = new ArrayList<>(Arrays.asList("T","D"));

    /**
     * Used when transfer allowances are only accepted on services operated by the issuing agency.
     */
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

        private MixedSystemTransferAllowance update(int fareValue) {
            int allowanceValue = Math.max(fareValue, value);
            return new MixedSystemTransferAllowance(allowanceValue, number - 1, expirationTime, agencyId);
        }

    }

    private static boolean doesNotCrossFareGates(int fromStopIndex, int toStopIndex, TransitLayer transitLayer){
        String fromStation = transitLayer.parentStationIdForStop.get(fromStopIndex);
        String toStation = transitLayer.parentStationIdForStop.get(toStopIndex);
        String fromFareZone = transitLayer.fareZoneForStop.get(fromStopIndex);
        String toFareZone = transitLayer.fareZoneForStop.get(toStopIndex);

        // If the fromStop and toStop are in the same parent station and fare zone, we have not crossed a fare gate.
        return fromStation.equals(toStation) && fromFareZone.equals(toFareZone);
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

        int alightStopIndex = -1;

        // Loop over rides to get to the state in forward-chronological order
        for (int ride = 0; ride < patterns.size(); ride ++) {
            int pattern = patterns.get(ride);
            RouteInfo route = transitLayer.routes.get(transitLayer.tripPatterns.get(pattern).routeIndex);
            // used for logging
            if (LOG_FARES) routeNames.add(route.route_short_name != null && !route.route_short_name.isEmpty() ?
                    route.route_short_name : route.route_long_name);

            // board stop for this ride
            int boardStopIndex = boardStops.get(ride);

            // alight stop for this ride
            alightStopIndex = alightStops.get(ride);

            int boardClockTime = boardTimes.get(ride);
            String fareId = getFareIdFromRouteId(route);
            Fare fare = fares.get(fareId);

            // Agency of the board route
            String receivingAgency = route.agency_id;

            // Agency that issued the currently held transfer allowance (possibly several rides ago)
            String issuingAgency = transferAllowance.agencyId;

            // Continue if boarding a route serving the paid area, without crossing fare gates since previous ride
            if (faresAvailableInPaidArea.contains(fareId)) {
                int fromStopIndex = alightStops.get(ride - 1);
                if (doesNotCrossFareGates(fromStopIndex, boardStopIndex, transitLayer)) continue;
            }

            // We are not staying within the pay area.  So...
            // Check for transferValue expiration
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
                    transferAllowance = transferAllowance.update(undiscountedPrice);
                } else {
                    // This agency will not accept transfer allowance.  Hold onto it, and pay full fare.
                    cumulativeFarePaid += undiscountedPrice;
                }
            } else {
                // Pay full fare and obtain transfer allowance
                cumulativeFarePaid += undiscountedPrice;
                transferAllowance = new MixedSystemTransferAllowance(priceToInt(fare.fare_attribute.price), fare
                                .fare_attribute.transfers,boardClockTime + fare.fare_attribute.transfer_duration,
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

        private FareSystemWrapper(Map<String, Fare> fares) {
            this.fares = fares;
        }
    }

    private static FareSystemWrapper loadFaresFromGTFS(TransitLayer transitLayer){
        Map<String, Fare> fares = new HashMap<>();
        // iterate through fares to record rules
        for (Fare fare : transitLayer.fares.values()){
            fares.putIfAbsent(fare.fare_id, fare);
        }
        return new FareSystemWrapper(fares);
    }
}