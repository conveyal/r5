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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Fare calculator for the MBTA, assuming use of CharlieCard where accepted.  For an overview of the logic of
 * calculateFares(), including numerous MBTA special cases, see https://files.indicatrix.org/charlie.pdf
 */
public class BostonInRoutingFareCalculator extends InRoutingFareCalculator {
    /** If true, log a random 1e-6 sample of fares for spot checking */
    public static final boolean LOG_FARES = false;

    private static final WeakHashMap<TransitLayer, FareSystemWrapper> fareSystemCache = new WeakHashMap<>();
    private RouteBasedFareRules fares;

    // Some fares may confer different transfer allowance values, but have the same issuing and acceptance rules.
    // For example, in Boston, the transfer allowances from inner and outer express bus fares have different values,
    // but they are issued and accepted under the same circumstances.
    private enum TransferRuleGroup { LOCAL_BUS, SUBWAY, EXPRESS_BUS, SL_AIRPORT, LOCAL_BUS_TO_SUBWAY, OUT_OF_SUBWAY,
        OTHER, NONE}

    // Map fare_id values from GTFS fare_attributes.txt to these transfer rule groups
    private static final String LOCAL_BUS_FARE_ID = "localBus";
    private static final String SUBWAY_FARE_ID = "subway";
    private static final Map<String, TransferRuleGroup> fareGroups = new HashMap<String, TransferRuleGroup>() {
        {put(LOCAL_BUS_FARE_ID, TransferRuleGroup.LOCAL_BUS); }
        {put(SUBWAY_FARE_ID, TransferRuleGroup.SUBWAY); }
        {put("innerExpressBus", TransferRuleGroup.EXPRESS_BUS); }
        {put("outerExpressBus", TransferRuleGroup.EXPRESS_BUS); }
        {put("slairport", TransferRuleGroup.SL_AIRPORT); }
    };

    private static final Set<List<TransferRuleGroup>> transferEligibleSequencePairs = new HashSet<>(
            Arrays.asList(
                    Arrays.asList(TransferRuleGroup.LOCAL_BUS, TransferRuleGroup.LOCAL_BUS),
                    Arrays.asList(TransferRuleGroup.SUBWAY, TransferRuleGroup.SUBWAY),
                    Arrays.asList(TransferRuleGroup.LOCAL_BUS, TransferRuleGroup.SUBWAY),
                    Arrays.asList(TransferRuleGroup.SUBWAY, TransferRuleGroup.LOCAL_BUS),
                    Arrays.asList(TransferRuleGroup.EXPRESS_BUS, TransferRuleGroup.SUBWAY),
                    Arrays.asList(TransferRuleGroup.SUBWAY, TransferRuleGroup.EXPRESS_BUS),
                    Arrays.asList(TransferRuleGroup.EXPRESS_BUS, TransferRuleGroup.LOCAL_BUS),
                    Arrays.asList(TransferRuleGroup.LOCAL_BUS, TransferRuleGroup.EXPRESS_BUS),
                    Arrays.asList(TransferRuleGroup.LOCAL_BUS_TO_SUBWAY, TransferRuleGroup.SUBWAY),
                    Arrays.asList(TransferRuleGroup.LOCAL_BUS_TO_SUBWAY, TransferRuleGroup.LOCAL_BUS)
            )
    );

    private static final String DEFAULT_FARE_ID = LOCAL_BUS_FARE_ID;
    private static final Set<String> stationsWithoutBehindGateTransfers = new HashSet<>(Arrays.asList(
            "place-coecl", "place-aport"));
    private static final Set<Set<String>> stationsConnected = new HashSet<>(Arrays.asList(new HashSet<>(Arrays.asList(
            "place-dwnxg", "place-pktrm"))));

    // Logging to facilitate debugging
    private static final Logger LOG = LoggerFactory.getLogger(BostonInRoutingFareCalculator.class);

    private MersenneTwister logRandomizer = LOG_FARES ? new MersenneTwister() : null;

    /**
     * There are a few reasons we need to extend the base TransferAllowance class for the MBTA:
     *
     * The value of a TransferAllowance is never greater than the subway fare.
     *
     * Local bus -> subway -> local bus is covered by one subway fare, while all other fares allow only one transfer.
     *
     * We also need to override atLeastAsGoodForAllFutureRedemptions because of special rules about Express Buses.
     */
    public class BostonTransferAllowance extends TransferAllowance {

        /**
         * What rules condition the issuance and acceptance of this transfer allowance?
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
         */
        private final TransferRuleGroup transferRuleGroup;

        /**
         * No transfer allowance
         */
        private BostonTransferAllowance () {
            super();
            this.transferRuleGroup = TransferRuleGroup.NONE;
        }

        /**
         * Explicitly set a TransferRuleGroup, and use the fare price as the value of the transfer allowance (e.g. in a
         * pay-the-difference fare system).
         * @param transferRuleGroup one of the enumerated TransferRuleGroup
         * @param fare used to set the number of transfers allowed, the validity duration, and the value.
         * @param startTime clock time when the validity of this transferAllowance starts.  The base constructor is
         *                  called with this time plus the fare's transfer_duration.
         */
        private BostonTransferAllowance (TransferRuleGroup transferRuleGroup, Fare fare, int startTime){
            super(priceToInt(fare.fare_attribute.price),
                    fare.fare_attribute.transfers,
                    startTime + fare.fare_attribute.transfer_duration);
            this.transferRuleGroup = transferRuleGroup;
        }

        /**
         * Determine the TransferRuleGroup from the fare_id.
         * @param fare used to set the transferRuleGroup, the number of transfers allowed, the validity duration, and
         *             the value if the value is less than the subway value.
         * @param startTime clock time when the validity of this transferAllowance starts.
         */
        private BostonTransferAllowance(Fare fare, int startTime){
            super(fare,
                    priceToInt(Math.min(fares.byId.get(SUBWAY_FARE_ID).fare_attribute.price, fare.fare_attribute.price)),
                    startTime + fare.fare_attribute.transfer_duration);
            this.transferRuleGroup = fareGroups.get(fare.fare_id);
        }

        /**
         * Create a new transfer allowance if the fare allows it; otherwise return previous transfer allowance.  Note
         * GTFS uses blank to indicate unlimited transfers, but gtfs-lib updates this to Integer.MAX_VALUE.
         */
        private BostonTransferAllowance updateTransferAllowance(Fare fare, int clockTime){
            if(fare.fare_attribute.transfers > 0){
                // if the boarding includes transfer privileges, set the values needed to use them in subsequent
                // journeyStages
                return new BostonTransferAllowance(fare, clockTime);
            } else {
                // We have boarded a service that does not provide a transfer allowance, preserve the previous transfer
                // allowance UNLESS we are coming from the subway, in which case any other service will require the user to
                // leave the paid area.
                if (this.transferRuleGroup == TransferRuleGroup.SUBWAY) {
                    // if we've gone from subway to a fare that does not allow transfers (e.g. Commuter Rail, Ferry), we
                    // could still transfer to a bus, but boarding the subway again would require full fare payment.
                    // This example arises in Boston for travel between Back Bay and South Station.  If you make this
                    // trip using Orange Line -> Red Line, you have full subway transfer privileges at South Station
                    // (e.g. to Silver Line 1 behind fare gates or Silver Line 4 on the surface).  But if you make it
                    // using Commuter Rail, you would need to pay full subway fare again to pass through the fare
                    // gates to access the SL1, though you'd still have a free transfer to the SL4.
                    return new BostonTransferAllowance(TransferRuleGroup.OUT_OF_SUBWAY,
                            fares.byId.get(SUBWAY_FARE_ID),
                            expirationTime);
                }
                //otherwise return the previous transfer privilege.
                return this;
            }
        }

        private BostonTransferAllowance localBusToSubwayTransferAllowance(){
            Fare fare = fares.byId.get(SUBWAY_FARE_ID);
            // Expiration time should be from original local bus boarding, not updated
            int expirationTime = this.expirationTime;
            return new BostonTransferAllowance(TransferRuleGroup.LOCAL_BUS_TO_SUBWAY, fare, expirationTime);
        }

        private BostonTransferAllowance checkForSubwayExit(int fromStopIndex, McRaptorSuboptimalPathProfileRouter
                .McRaptorState state, TransitLayer transitLayer){
            String fromStation = transitLayer.parentStationIdForStop.get(fromStopIndex);
            int toStopIndex = state.stop;
            String toStation = transitLayer.parentStationIdForStop.get(toStopIndex);
            if (platformsConnected(fromStopIndex, fromStation, toStopIndex, toStation)) {
                // Have not exited subway through fare gates; maintain transfer privilege
                return this;
            } else {
                // exited subway through fare gates; value can still be used for transfers to bus, but a subsequent
                // subway boarding requires payment of full subway fare.
                Fare fare = fares.byId.get(SUBWAY_FARE_ID);
                // Expiration time should be from original transfer allowance, not updated
                int expirationTime = this.expirationTime;
                return new BostonTransferAllowance(TransferRuleGroup.OUT_OF_SUBWAY, fare, expirationTime);
            }
        }

        @Override
        public boolean atLeastAsGoodForAllFutureRedemptions(TransferAllowance other) {
            return super.atLeastAsGoodForAllFutureRedemptions(other) &&
                    this.transferRuleGroup == ((BostonTransferAllowance) other).transferRuleGroup;
        }

    }

    private final BostonTransferAllowance noTransferAllowance = new BostonTransferAllowance();

    private static int priceToInt(double price) {return (int) (price * 100);} // usd to cents

    private static int payFullFare(Fare fare) {return priceToInt(fare.fare_attribute.price);}

    // Assume commuter rail routes are not enumerated in fare_rules
    // All routes with route_type 2 use the same Commuter Rail system of zones except FIXME CapeFlyer and Foxboro
    private static String getRouteId(RouteInfo route) {return route.route_type == 2 ? null : route.route_id;}

    /** Is it possibly possible that these services are connected behind the fare gates (i.e. is there anywhere in the system
     * where both services share a station and can be transferred between without leaving the paid area).
     */
    private static boolean servicesConnectedBehindFareGates(TransferRuleGroup issuing, TransferRuleGroup receiving){
        return ((issuing == TransferRuleGroup.SUBWAY || issuing == TransferRuleGroup.SL_AIRPORT) &&
                (receiving == TransferRuleGroup.SUBWAY || receiving == TransferRuleGroup.SL_AIRPORT));
    }

    private static boolean platformsConnected(int fromStopIndex, String fromStation, int toStopIndex, String toStation){
        return (fromStopIndex == toStopIndex ||  // same platform

                // different platforms, same station, in stations with behind-gate transfers between platforms
                (fromStation != null && fromStation.equals(toStation) &&
                        // e.g. Copley has same parent station, but no behind-the-gate transfers between platforms
                        !stationsWithoutBehindGateTransfers.contains(toStation)) ||
                // different stations connected behind faregates
                // e.g. Park Street and Downtown Crossing are connected by the Winter Street Concourse
                stationsConnected.contains(new HashSet<>(Arrays.asList(fromStation, toStation))));
    }

    @Override
    public FareBounds calculateFare(McRaptorSuboptimalPathProfileRouter.McRaptorState state, int maxClockTime) {

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

            // board stop for this ride
            int boardStopIndex = boardStops.get(ride);
            String boardStation = transitLayer.parentStationIdForStop.get(boardStopIndex);
            String boardStopZoneId = transitLayer.fareZoneForStop.get(boardStopIndex);

            // alight stop for this ride
            alightStopIndex = alightStops.get(ride);
            String alightStopZoneId = transitLayer.fareZoneForStop.get(alightStopIndex);

            int boardClockTime = boardTimes.get(ride);

            // used for logging
            if (LOG_FARES) routeNames.add(route.route_short_name != null && !route.route_short_name.isEmpty() ?
                    route.route_short_name : route.route_long_name);

            String routeId = getRouteId(route);

            Fare fare = fares.getFareOrDefault(routeId, boardStopZoneId, alightStopZoneId);

            // TransferAllowance is from a past ride (possibly several rides ago, if, say, commuter rail was ridden between
            // local bus trips.
            // Issuing may not necessarily be the previous ride. For instance, if you ride local bus -> commuter rail,
            // your transfer allowance after alighting is still LOCAL_BUS; the CharlieCard system doesn't know you rode commuter
            // rail versus walked really fast, etc.
            TransferRuleGroup issuing = transferAllowance.transferRuleGroup;
            TransferRuleGroup receiving = fareGroups.get(fare.fare_id);

            // servicesConnectedBehindFareGates contains an implicit bounds check that ride >= 1
            if (servicesConnectedBehindFareGates(issuing, receiving)) {
                int fromStopIndex = alightStops.get(ride - 1);
                String fromStation = transitLayer.parentStationIdForStop.get(fromStopIndex);
                // if the previous alighting stop and this boarding stop are connected behind fare
                // gates (and without riding a vehicle!), continue to the next ride. There is no CharlieCard tap
                // and thus for fare purposes these are a single ride.
                if (platformsConnected(fromStopIndex, fromStation, boardStopIndex, boardStation)) continue;
            }

            // Check for transferValue expiration
            // This is not done on behind-faregate transfers because once you're in the subway, you don't tap your
            // CharlieCard again, so, if you so desire, you can ride forever 'neath the streets of Boston (or at least
            // until system closing).
            if (transferAllowance.hasExpiredAt(boardTimes.get(ride))) transferAllowance = noTransferAllowance;

            // We are doing a transfer that is not behind faregates, check if we might be able to redeem a transfer
            boolean tryToRedeemTransfer =
                    transferEligibleSequencePairs.contains(Arrays.asList(issuing, receiving)) &&
                    transferAllowance.value > 0 && // last two checks probably not needed as issuing will be NONE in these cases
                    transferAllowance.number > 0;

            // If the fare for this boarding accepts transfers and transfer value is available, attempt to use it.
            if (tryToRedeemTransfer) {
                // Handle special cases first
                // Special case: transfer is local bus -> subway
                if (issuing == TransferRuleGroup.LOCAL_BUS && receiving == TransferRuleGroup.SUBWAY) {
                    // pay difference and set special transfer allowance
                    cumulativeFarePaid += transferAllowance.payDifference(priceToInt(fare.fare_attribute.price));
                    transferAllowance = transferAllowance.localBusToSubwayTransferAllowance();
                }
                // Special case: route prefix is (local bus -> subway)
                else if (issuing == TransferRuleGroup.LOCAL_BUS_TO_SUBWAY){
                    // local bus -> subway -> bus special case
                    if (receiving == TransferRuleGroup.LOCAL_BUS) {
                        //Don't increment cumulativeFarePaid, just clear transferAllowance. Local bus->subway->local bus is a free transfer.
                        transferAllowance = noTransferAllowance;
                    } else { // (local bus -> subway -> anything other than local bus) requires full fare on third
                        // boarding
                        // TODO suspect this is not true but other privileges are undocumented. On the ground verification
                        // required. For instance, I (MWC) suspect local bus -> subway -> inner express bus costs 1.70 + 0.55 + 1.75 = 4
                        cumulativeFarePaid += payFullFare(fare);
                        transferAllowance = transferAllowance.updateTransferAllowance(fare, boardClockTime);
                    }
                } else {
                    // If we are not facing one of the special cases above, and redeem the transfer, exhausting its value;
                    cumulativeFarePaid += transferAllowance.payDifference(priceToInt(fare.fare_attribute.price));
                    transferAllowance = noTransferAllowance;
                }
            } else { // don't try to use transferValue; pay the full fare for this ride
                cumulativeFarePaid += payFullFare(fare);
                transferAllowance = transferAllowance.updateTransferAllowance(fare, boardClockTime);
            }
        }

        // warning: reams of log output
        // only log 1/1000000 of the fares
        if (LOG_FARES && logRandomizer.nextInt(1000000) == 42) {
            LOG.info("Fare for {}: ${}", String.join(" -> ", routeNames), String.format("%.2f", cumulativeFarePaid / 100D));
        }

        // Check for out-of-subway transfers before returning the transfer allowance. We want to return the
        // correct transfer allowance given the next boarding stop, even though we don't know the next ride.
        // If state is the result of an "on-street transfer" (excluding platform-to-platform within stations where
        // platforms are connected) to another subway stop, we do not know the next ride, but know that it cannot be a
        // free boarding to the subway. MBTA doesn't have designated free transfer stops, although it would be a good
        // idea e.g. between the platforms of Copley, Charles/MGH and Bowdoin, or Cleveland Circle and Reservoir.
        if (transferAllowance.transferRuleGroup == TransferRuleGroup.SUBWAY){
            transferAllowance = transferAllowance.checkForSubwayExit(alightStopIndex, state, transitLayer);
        }

        return new FareBounds(cumulativeFarePaid, transferAllowance.tightenExpiration(maxClockTime));
    }

    @Override
    public String getType() {
        return "boston";
    }

    private static class FareSystemWrapper{
        public RouteBasedFareRules fares;

        private FareSystemWrapper(RouteBasedFareRules fares) {
            this.fares = fares;
        }
    }

    private static FareSystemWrapper loadFaresFromGTFS(TransitLayer transitLayer){
        RouteBasedFareRules fares = new RouteBasedFareRules();
        // iterate through fares to record rules
        for (Fare fare : transitLayer.fares.values()){
            fares.addFareRules(fare);
        }
        return new FareSystemWrapper(fares);
    }
}