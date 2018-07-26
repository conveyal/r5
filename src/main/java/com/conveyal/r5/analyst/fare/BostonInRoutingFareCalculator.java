package com.conveyal.r5.analyst.fare;

import com.conveyal.gtfs.model.Fare;
import com.conveyal.gtfs.model.FareAttribute;
import com.conveyal.gtfs.model.Stop;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Fare calculator for the MBTA, assuming use of CharlieCard where accepted
 */
public class BostonInRoutingFareCalculator extends InRoutingFareCalculator {

    private static final WeakHashMap<TransitLayer, FareSystemWrapper> fareSystemCache = new WeakHashMap<>();
    private RouteBasedFareRules fares;
    private static final String LOCAL_BUS = "localBus";
    private static final String INNER_EXPRESS_BUS = "innerExpressBus";
    private static final String OUTER_EXPRESS_BUS = "outerExpressBus";
    private static final String SUBWAY = "subway";
    private static final String SL_AIRPORT = "slairport";
    private static final Set<List<String>> transferEligibleSequences = new HashSet<>(
            Arrays.asList(
                    Arrays.asList(LOCAL_BUS, LOCAL_BUS),
                    Arrays.asList(SUBWAY, SUBWAY),
                    Arrays.asList(LOCAL_BUS, SUBWAY),
                    Arrays.asList(SUBWAY, LOCAL_BUS),
                    Arrays.asList(INNER_EXPRESS_BUS, SUBWAY),
                    Arrays.asList(SUBWAY, INNER_EXPRESS_BUS),
                    Arrays.asList(OUTER_EXPRESS_BUS, SUBWAY),
                    Arrays.asList(SUBWAY, OUTER_EXPRESS_BUS),
                    Arrays.asList(INNER_EXPRESS_BUS, LOCAL_BUS),
                    Arrays.asList(LOCAL_BUS, INNER_EXPRESS_BUS),
                    Arrays.asList(OUTER_EXPRESS_BUS, LOCAL_BUS),
                    Arrays.asList(LOCAL_BUS, OUTER_EXPRESS_BUS)
            )
    );
    private static final String DEFAULT_FARE_ID = LOCAL_BUS;
    private static final Set<String> stationsWithoutBehindGateTransfers = new HashSet<>(Arrays.asList(
            "place-coecl", "place-aport"));
    private static final Set<Set<String>> stationsConnected = new HashSet<>(Arrays.asList(
            new HashSet<>(Arrays.asList("place-dwnxg", "park"))));
    private static final Logger LOG = LoggerFactory.getLogger(BostonInRoutingFareCalculator.class);

    private MersenneTwister logRandomizer = new MersenneTwister();

    /**
     * For the MBTA, the value of a TransferAllowance is never greater than the subway fare.  We need to handle
     * the special case of local bus -> subway -> local bus being covered by one subway fare, while all other fares
     * allow only one transfer. We also need to override the base canTransferPrivilege dominate because of special
     * rules about Express Buses.
     */
    public class BostonTransferAllowance extends TransferAllowance {
        public BostonTransferAllowance () {
            /* restore default constructor */
        }

        public BostonTransferAllowance(Fare fare, int startTime){
            super(fare, priceToInt(Math.min(fares.byId.get(SUBWAY).fare_attribute.price, fare.fare_attribute
                    .price)), startTime);
        }

        public BostonTransferAllowance(String fareId, int value, int number, int expirationTime) {
            super(fareId, value, number, expirationTime);
        }

        public BostonTransferAllowance updateTransferAllowance(Fare fare, int clockTime){
            if(fare.fare_attribute.transfers > 0){
                // if the boarding includes transfer privileges, set the values needed to use them in subsequent
                // journeyStages
                return new BostonTransferAllowance(fare, clockTime);
            } else {
                //otherwise return the previous transfer privilege.
                return this;
            }
        }

        public BostonTransferAllowance localBusToSubwayTransferAllowance(int clockTime){
            String fareId = LOCAL_BUS;
            FareAttribute fareAttribute = fares.byId.get(LOCAL_BUS).fare_attribute;
            int value = priceToInt(Math.min(fares.byId.get(SUBWAY).fare_attribute.price, fareAttribute.price));
            int expirationTime = clockTime + fareAttribute.transfer_duration;
            // transferAllowance.number set to -1 as a signal to check for local bus -> subway -> bus special case;
            return new BostonTransferAllowance(fareId, value, -1, expirationTime);
        }

        @Override
        public boolean isAsGoodAsOrBetterThanForAllPossibleFutureTrips (TransferAllowance other) {
            return super.isAsGoodAsOrBetterThanForAllPossibleFutureTrips(other) &&
                    this.getTransferPrivilegeSource().equals(((BostonTransferAllowance) other).getTransferPrivilegeSource());
        }

        /**
         * Where did this transfer allowance come from?
         *
         * Transfer allowances from subway, local bus and express bus are all non-comparable, because
         * 1. Subway allows boarding local and express buses, and other subways that are behind the same fare gates
         * 2. Local bus allows boarding local and express buses and any subway
         * 3. Express bus allows boading local buses or subway but not express buses
         *
         * Consider a counter example. If you are going from Coolidge Corner to Newton Center in Boston, you can take the C
         * line to Cleveland Circle, walk the block to Reservoir, and take the D line to Newton Center. This costs
         * $4.50 - two subway fares - because there is no behind-the-gates transfer between Riverside and Newton Center.
         * Suppose there were a bus that ran along Beacon Street from Coolidge Corner to Cleveland Circle, and then you could
         * again walk to Reservoir and take the D line. This costs $2.25 (a bus ride and then a transfer to the subway).
         * At Cleveland Circle, the transfer allowances from the two services are equal* so the train route could dominate the
         * bus route, even though the bus route yields a cheaper overall route. To prevent this, we consider the train
         * transfer allowance to be incomparable to the bus transfer allowance.
         *
         * * actually, due to implementation, the transfer allowance from the train is 2.25, vs. 1.70 for the bus, because the algorithm doesn't
         *   know there is no behind the gates transfer to any other train at Cleveland Circle.
         * @return
         */
        private TransferPrivilegeSource getTransferPrivilegeSource() {
            if (INNER_EXPRESS_BUS.equals(this.fareId) || OUTER_EXPRESS_BUS.equals(this.fareId))
                return TransferPrivilegeSource.EXPRESS_BUS;
            else if (SUBWAY.equals(this.fareId)) return TransferPrivilegeSource.SUBWAY;
            else if (LOCAL_BUS.equals(this.fareId)) return TransferPrivilegeSource.LOCAL_BUS;
            else return TransferPrivilegeSource.OTHER;
        }

    }

    private static enum TransferPrivilegeSource { LOCAL_BUS, SUBWAY, EXPRESS_BUS, OTHER };

    private final BostonTransferAllowance noTransferAllowance = new BostonTransferAllowance();

    private static int priceToInt(double price) {return (int) (price * 100);} // usd to cents

    private static int payFullFare(Fare fare) {return priceToInt(fare.fare_attribute.price);}

    // Assume commuter rail routes are not enumerated in fare_rules
    // All routes with route_type 2 use the same Commuter Rail system of zones except FIXME CapeFlyer and Foxboro
    private static String getRouteId(RouteInfo route) {return route.route_type == 2 ? null : route.route_id;}

    private static boolean isFreeTransferCandidate(String previousFareId, String fareId){
        return (SUBWAY.equals(previousFareId) || SL_AIRPORT.equals(previousFareId)) &&
                (SUBWAY.equals(fareId) || SL_AIRPORT.equals(fareId));
    }

    @Override
    public FareBounds calculateFare(McRaptorSuboptimalPathProfileRouter.McRaptorState state) {

        // First, load fare data from GTFS
        if (fares == null){
            synchronized (this) {
                if (fares == null){
                    synchronized (fareSystemCache) {
                        FareSystemWrapper fareSystem = fareSystemCache.computeIfAbsent(this.transitLayer,
                                BostonInRoutingFareCalculator::loadFaresFromGTFS);
                        this.fares = fareSystem.fares;
                        this.fares.defaultFare = DEFAULT_FARE_ID;
                    }
                }
            }
        }

        // Initialize: haven't boarded, paid a fare, or received a transfer allowance
        int cumulativeFarePaid = 0;
        BostonTransferAllowance transferAllowance = noTransferAllowance;

        // Extract relevant data about journey stages
        TIntList patterns = new TIntArrayList();
        TIntList boardStops = new TIntArrayList();
        TIntList alightStops = new TIntArrayList();
        TIntList times = new TIntArrayList();

        List<String> routeNames = new ArrayList();

        while (state != null) {
            if (state.pattern == -1) {
                state = state.back;
                continue; // on the street, not on transit
            }
            patterns.add(state.pattern);
            alightStops.add(state.stop);
            boardStops.add(transitLayer.tripPatterns.get(state.pattern).stops[state.boardStopPosition]);
            times.add(state.time);
            state = state.back;
        }

        // reverse data about the journey stages so we can step forward through them
        patterns.reverse();
        alightStops.reverse();
        boardStops.reverse();
        times.reverse();

        for (int journeyStage = 0; journeyStage < patterns.size(); journeyStage ++) {
            int pattern = patterns.get(journeyStage);
            RouteInfo route = transitLayer.routes.get(transitLayer.tripPatterns.get(pattern).routeIndex);
            int boardStopIndex = boardStops.get(journeyStage);
            String boardStation = transitLayer.parentStationIdForStop.get(boardStopIndex);
            String boardStopZoneId = transitLayer.fareZoneForStop.get(boardStopIndex);

            int alightStopIndex = alightStops.get(journeyStage);
            String alightStopZoneId = transitLayer.fareZoneForStop.get(alightStopIndex);

            int clockTime = times.get(journeyStage);

            routeNames.add(route.route_short_name != null && !route.route_short_name.isEmpty() ?
                    route.route_short_name : route.route_long_name);

            String routeId = getRouteId(route);

            Fare fare = fares.getFareOrDefault(routeId, boardStopZoneId, alightStopZoneId);

            // Check for transferValue expiration
            if (transferAllowance.hasExpiredAt(times.get(journeyStage))) transferAllowance = noTransferAllowance;

            if (fare == null) {
                throw new IllegalArgumentException("FARE IS NULL");
            }

            if (isFreeTransferCandidate(transferAllowance.fareId, fare.fare_id)) {
                int previousAlightStopIndex = alightStops.get(journeyStage - 1);
                String previousAlightStation = transitLayer.fareZoneForStop.get(previousAlightStopIndex);
                // and if the previous alighting stop and this boarding stop are connected behind fare
                // gates, continue to the next journeyStage.
                if (previousAlightStopIndex == boardStopIndex ||
                        (previousAlightStation != null && previousAlightStation.equals(boardStation) &&
                                // e.g. Copley has same parent station, but no behind-the-gate transfers between platforms
                                !stationsWithoutBehindGateTransfers.contains(boardStation)) ||
                        // e.g. Park Street and Downtown Crossing are connected by the Winter Street Concourse
                        stationsConnected.contains(new HashSet<>(Arrays.asList(previousAlightStation, boardStation))))
                continue;
            }

            boolean tryToRedeemTransfer = transferEligibleSequences.contains(
                    Arrays.asList(transferAllowance.fareId, fare.fare_id))
                    && transferAllowance.value > 0
                    && transferAllowance.number != 0; // Negative values signal special cases

            // If the fare for this boarding accepts transfers and transferValue is available, attempt to use it.
            if (tryToRedeemTransfer) {

                // Handle special cases first
                if (transferAllowance.number < 0){
                    if (transferAllowance.number == -1) { // route prefix is (local bus -> subway)
                        if (LOCAL_BUS.equals(fare.fare_id)){ // local bus -> subway -> bus special case
                            //Don't increment cumulativeFarePaid, just clear transferAllowance.
                            transferAllowance = noTransferAllowance;
                            continue;
                        } else { // (local bus -> subway -> anything other than local bus) requires full fare on
                            // third boarding
                            cumulativeFarePaid += payFullFare(fare);
                            transferAllowance = transferAllowance.updateTransferAllowance(fare, clockTime);
                            continue;
                        }
                    } else {
                        throw new UnsupportedOperationException("Negative transfer allowance!");
                    }
                }

                 if (LOCAL_BUS.equals(transferAllowance.fareId) && SUBWAY.equals(fare.fare_id)) { // local bus -> subway
                        cumulativeFarePaid += transferAllowance.payDifference(priceToInt(fare.fare_attribute
                                .price));
                        transferAllowance = transferAllowance.localBusToSubwayTransferAllowance(clockTime);
                        continue;
                    }

                // If we are not facing one of the special cases above, redeem the transfer.
                cumulativeFarePaid += transferAllowance.payDifference(priceToInt(fare.fare_attribute.price));
                transferAllowance = noTransferAllowance;
            } else { // don't try to use transferValue; pay the full fare for this journeyStage
                cumulativeFarePaid += payFullFare(fare);
                transferAllowance = transferAllowance.updateTransferAllowance(fare, clockTime);
            }
        }

        // warning: reams of log output
        // only log 1/10000 of the fares
        //if (logRandomizer.nextInt(1000000) == 42) {
          //  LOG.info("Fare for {}: ${}", String.join(" -> ", routeNames), String.format("%.2f", cumulativeFarePaid / 100D));
        //}

        return new FareBounds(cumulativeFarePaid, transferAllowance.clean());
    }

    @Override
    public String getType() {
        return "boston";
    }

    private static class FareSystemWrapper{
        public RouteBasedFareRules fares;

        public FareSystemWrapper(RouteBasedFareRules fares) {
            this.fares = fares;
        }
    }

    public static FareSystemWrapper loadFaresFromGTFS(TransitLayer transitLayer){
        RouteBasedFareRules fares = new RouteBasedFareRules();
        // iterate through fares to record rules
        for (Fare fare : transitLayer.fares.values()){
            fares.addFareRules(fare);
        }
        return new FareSystemWrapper(fares);
    }
}