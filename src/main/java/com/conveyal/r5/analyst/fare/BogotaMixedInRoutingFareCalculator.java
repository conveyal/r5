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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Fare calculator for systems in which:
 * - Boarding any route has a cost that does not depend on where one alights (e.g. no zone-based fares)
 * - A set of routes offers mutually recognized transfers. Other routes do not issue or accept transfers.
 * - Transfers allow users to pay the difference when boarding a more expensive route
 * - Certain stops are connected within paid areas, allowing unlimited free transfers between them (behind fare gates)
 * - The most expensive fare allows entering fare gates once, and may allow other out-of-paid-area boardings
 * This calculator is designed for regions with mixed formal and informal transit services such as Bogota, in which the
 * formal services issue and accept transfer privileges(e.g. between BRT and cheaper zonal/feeder routes) and the
 * semi-formal services (e.g. cash-based private operators) do not.
 * This implementation relies on non-standard conventions being used in input GTFS:
 * - routes:agency_id equals the corresponding fare_attributes:fare_id
 * - Transfers are only accepted by routes with corresponding fare_attributes:transfers > 0.
 * - unlimited free transfers are allowed between stops that share the same (non-blank) parent_station
 */
public class BogotaMixedInRoutingFareCalculator extends InRoutingFareCalculator {
    /** If true, log a random 1e-6 sample of fares for spot checking */
    public static final boolean LOG_FARES = false;

    private static final WeakHashMap<TransitLayer, FareSystemWrapper> fareSystemCache = new WeakHashMap<>();
    private Map<String, Fare> fares;
    // With a standard TransferAllowance, paying the fare to enter a station would confer a transfer allowance with
    // that full fare, which we assume is the most expensive fare in the system.  But in practice, entering a paid
    // area for a subsequent time in the same itinerary would require full payment again.  So the effective value of
    // the transfer allowance is actually the price of the second highest fare that accepts transfers.
    private int secondHighestFarePrice;

    // Logging to facilitate debugging
    private static final Logger LOG = LoggerFactory.getLogger(BogotaMixedInRoutingFareCalculator.class);

    private MersenneTwister logRandomizer = LOG_FARES ? new MersenneTwister() : null;

    private static int priceToInt(double price) {return (int) (price);} // No conversion for now

    private class MixedSystemTransferAllowance extends TransferAllowance {
        private final boolean redeemableAtFareGates;

        // An empty allowance with no transfer privileges
        private MixedSystemTransferAllowance () {
            super();
            this.redeemableAtFareGates = false;
        }

        private MixedSystemTransferAllowance (int value, int number, int expirationTime, boolean obtainedAtFareGates){
            super(value, number, expirationTime);
            // If a transfer allowance is obtained at fare gates, it cannot be used to enter fare gates again.
            // Conversely, if a transfer allowance was not obtained at fare gates, it can be used at fare gates later
            // in an itinerary.
            this.redeemableAtFareGates = !obtainedAtFareGates;
        }

        private MixedSystemTransferAllowance redeemForOneRide(int fareValue, boolean obtainedAtFareGates) {
            int allowanceValue = obtainedAtFareGates ? secondHighestFarePrice : Math.max(fareValue, value);
            return new MixedSystemTransferAllowance(allowanceValue, number - 1, expirationTime, obtainedAtFareGates);
        }
    }

    private boolean withinPaidArea(int fromStopIndex, int toStopIndex){
        String fromParentStation = transitLayer.parentStationIdForStop.get(fromStopIndex);
        String toParentStation = transitLayer.parentStationIdForStop.get(toStopIndex);
        return fromParentStation != null && fromParentStation.equals(toParentStation);
    }

    @Override
    public FareBounds calculateFare(McRaptorSuboptimalPathProfileRouter.McRaptorState state, int maxClockTime) {

        // First, load fare data from GTFS
        if (fares == null){
            synchronized (this) {
                if (fares == null){
                    synchronized (fareSystemCache) {
                        FareSystemWrapper fareSystem = fareSystemCache.computeIfAbsent(this.transitLayer,
                                BogotaMixedInRoutingFareCalculator::loadFaresFromGTFS);
                        this.fares = fareSystem.fares;
                        this.secondHighestFarePrice = fareSystem.secondHighestFarePrice;
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
            Fare fare = fares.get(route.agency_id); // relies on non-standard convention described in class javadoc

            // We are not staying within the paid area.  So...
            // boarding at a station implies passing through fare gates.
            boolean passingThroughFareGates = transitLayer.parentStationIdForStop.get(boardStopIndex) != null;
            // Check if enough time has elapsed for transfer allowance to expire
            if (transferAllowance.hasExpiredAt(boardTimes.get(ride))) transferAllowance = new MixedSystemTransferAllowance();

            // Then check if a transfer might be redeemable
            boolean transferValueAvailable =
                    transferAllowance.value > 0 &&
                    transferAllowance.number > 0 &&
                    (transferAllowance.redeemableAtFareGates || !passingThroughFareGates);

            int undiscountedPrice = priceToInt(fare.fare_attribute.price);

            if (transferValueAvailable) { // If transfer value is available...
                if (fare.fare_attribute.transfers > 0) { // and, following above convention, this route accepts it...
                    // Pay difference and set updated transfer allowance
                    cumulativeFarePaid += transferAllowance.payDifference(undiscountedPrice);
                    transferAllowance = transferAllowance.redeemForOneRide(undiscountedPrice, passingThroughFareGates);
                } else {
                    // This route will not accept currently held transfer allowance.  Hold onto it, and pay full fare.
                    cumulativeFarePaid += undiscountedPrice;
                }
            } else {
                // Pay full fare and obtain new transfer allowance
                cumulativeFarePaid += undiscountedPrice;
                transferAllowance = new MixedSystemTransferAllowance(priceToInt(fare.fare_attribute.price),
                        fare.fare_attribute.transfers,boardClockTime + fare.fare_attribute.transfer_duration,
                        passingThroughFareGates);
            }
        }

        // warning: reams of log output
        // only log 1/1000000 of the fares
        if (LOG_FARES && logRandomizer.nextInt(1000000) == 42) {
            LOG.info("Fare for {}: ${}", String.join(" -> ", routeNames), cumulativeFarePaid);
        }

        return new FareBounds(cumulativeFarePaid, transferAllowance.tightenExpiration(maxClockTime));
    }

    @Override
    public String getType() {
        return "mixed-system";
    }

    private static class FareSystemWrapper{
        public Map<String, Fare> fares;
        public int secondHighestFarePrice;

        private FareSystemWrapper(Map<String, Fare> fares, int secondHighestFarePrice) {
            this.fares = fares;
            this.secondHighestFarePrice = secondHighestFarePrice;
        }
    }

    private static FareSystemWrapper loadFaresFromGTFS(TransitLayer transitLayer){
        Map<String, Fare> fares = new HashMap<>();

        int highestFarePrice = 0, secondHighestFarePrice = 0;
        // iterate through fares to record rules
        for (Fare fare : transitLayer.fares.values()){
            fares.putIfAbsent(fare.fare_id, fare);
            if (fare.fare_attribute.transfers > 0 && fare.fare_attribute.price >= highestFarePrice){
                secondHighestFarePrice = highestFarePrice;
                highestFarePrice = priceToInt(fare.fare_attribute.price);
            }
        }

        return new FareSystemWrapper(fares, secondHighestFarePrice);
    }
}