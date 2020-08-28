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
    private enum TransferRuleGroup { LOCAL_BUS, SUBWAY, EXPRESS_BUS, SL_FREE, LOCAL_BUS_TO_SUBWAY,
        OTHER, NONE}

    // Map fare_id values from GTFS fare_attributes.txt to these transfer rule groups
    private static final String LOCAL_BUS_FARE_ID = "localBus";
    private static final String SUBWAY_FARE_ID = "subway";
    private static final Map<String, TransferRuleGroup> fareGroups = new HashMap<String, TransferRuleGroup>() {
        {put(LOCAL_BUS_FARE_ID, TransferRuleGroup.LOCAL_BUS); }
        {put(SUBWAY_FARE_ID, TransferRuleGroup.SUBWAY); }
        // okay for inner and outer express buses to use same rule group, as they have the same
        // transfer privileges
        {put("innerExpressBus", TransferRuleGroup.EXPRESS_BUS); }
        {put("outerExpressBus", TransferRuleGroup.EXPRESS_BUS); }
        {put("slairport", TransferRuleGroup.SL_FREE); }
    };

    private static final Set<List<TransferRuleGroup>> transferEligibleSequencePairs = new HashSet<>(
            Arrays.asList(
                    Arrays.asList(TransferRuleGroup.LOCAL_BUS, TransferRuleGroup.LOCAL_BUS),
                    // Subway -> Subway only eligible if within fare gates, which is handled separately
                    // since it does not require a fare interaction
                    //Arrays.asList(TransferRuleGroup.SUBWAY, TransferRuleGroup.SUBWAY),
                    Arrays.asList(TransferRuleGroup.LOCAL_BUS, TransferRuleGroup.SUBWAY),
                    Arrays.asList(TransferRuleGroup.SUBWAY, TransferRuleGroup.LOCAL_BUS),
                    Arrays.asList(TransferRuleGroup.EXPRESS_BUS, TransferRuleGroup.SUBWAY),
                    Arrays.asList(TransferRuleGroup.SUBWAY, TransferRuleGroup.EXPRESS_BUS),
                    Arrays.asList(TransferRuleGroup.EXPRESS_BUS, TransferRuleGroup.LOCAL_BUS),
                    Arrays.asList(TransferRuleGroup.LOCAL_BUS, TransferRuleGroup.EXPRESS_BUS),
                    // see comment about SUBWAY, SUBWAY
                    //Arrays.asList(TransferRuleGroup.LOCAL_BUS_TO_SUBWAY, TransferRuleGroup.SUBWAY),
                    Arrays.asList(TransferRuleGroup.LOCAL_BUS_TO_SUBWAY, TransferRuleGroup.LOCAL_BUS)

                    // No free transfers from SL_FREE, except behind gates in the subway handled elsewhere
            )
    );

    private static final Set<TransferRuleGroup> modesWithBehindGateTransfers = new HashSet<>(Arrays.asList(TransferRuleGroup.SUBWAY, TransferRuleGroup.SL_FREE));

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
        public final TransferRuleGroup transferRuleGroup;

        /**
         * Once the subway is ridden, if you leave the subway, you can't get back on for free.
         *
         * This does not matter during the fare calculation loop, only when partial fares are compared, so we only
         * bother to set it then.
         */
        public final boolean behindGates;

        /**
         * No transfer allowance
         */
        private BostonTransferAllowance () {
            super();
            this.transferRuleGroup = TransferRuleGroup.NONE;
            this.behindGates = false;
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
            this.behindGates = false;
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
            this.behindGates = false;
        }

        /** Used to set whether the rider is still behind the fare gates, and to tighten expiration times */
        private BostonTransferAllowance(int value, int number, int expirationTime, TransferRuleGroup transferRuleGroup,
                                        boolean behindGates) {
            super(value, number, expirationTime);
            this.transferRuleGroup = transferRuleGroup;
            this.behindGates = behindGates;
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
                //otherwise return the previous transfer privilege, which the user can hold on to to use later.
                return this;
            }
        }

        private BostonTransferAllowance localBusToSubwayTransferAllowance(){
            Fare fare = fares.byId.get(SUBWAY_FARE_ID);
            // Expiration time should be from original local bus boarding, not updated
            int expirationTime = this.expirationTime;
            return new BostonTransferAllowance(TransferRuleGroup.LOCAL_BUS_TO_SUBWAY, fare, expirationTime);
        }

        /** called at the end of the fare calc loop to record whether the last state is behind gates or not. */
        private BostonTransferAllowance setBehindGates (boolean behindGates) {
            if (behindGates == this.behindGates) return this;
            else return new BostonTransferAllowance(this.value, this.number, this.expirationTime, this.transferRuleGroup, behindGates);
        }

        @Override
        public boolean atLeastAsGoodForAllFutureRedemptions(TransferAllowance other) {
            return super.atLeastAsGoodForAllFutureRedemptions(other) &&
                    this.transferRuleGroup == ((BostonTransferAllowance) other).transferRuleGroup &&
                    // if this is behind gates, or other is not behind gates, they are comparable
                    // if other is behind gates and this is not, it could possibly be better.
                    (this.behindGates || !((BostonTransferAllowance) other).behindGates);
        }

        public BostonTransferAllowance tightenExpiration (int maxClockTime) {
            // copied from TransferAllowance but need to override so that everything stays a BostonTransferAllowance
            int expirationTime = Math.min(this.expirationTime, maxClockTime);
            return new BostonTransferAllowance(this.value, this.number, expirationTime, this.transferRuleGroup, this.behindGates);
        }

    }

    private final BostonTransferAllowance noTransferAllowance = new BostonTransferAllowance();

    private static int priceToInt(double price) {return (int) Math.round(price * 100);} // usd to cents

    private static int payFullFare(Fare fare) {return priceToInt(fare.fare_attribute.price);}

    // Assume commuter rail routes are not enumerated in fare_rules
    // All routes with route_type 2 use the same Commuter Rail system of zones except FIXME CapeFlyer and Foxboro
    private static String getRouteId(RouteInfo route) {return route.route_type == 2 ? null : route.route_id;}

    /** Is it possibly possible that these services are connected behind the fare gates (i.e. is there anywhere in the system
     * where both services share a station and can be transferred between without leaving the paid area).
     */
    private static boolean servicesConnectedBehindFareGates(TransferRuleGroup issuing, TransferRuleGroup receiving){
        return ((issuing == TransferRuleGroup.SUBWAY || issuing == TransferRuleGroup.SL_FREE) &&
                (receiving == TransferRuleGroup.SUBWAY || receiving == TransferRuleGroup.SL_FREE));
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

            // Check if there was actually a fare interaction 
            if (ride > 0) {
                int prevPattern = patterns.get(ride - 1);
                RouteInfo prevRoute = transitLayer.routes.get(transitLayer.tripPatterns.get(prevPattern).routeIndex);

                // board stop for this ride
                int prevBoardStopIndex = boardStops.get(ride - 1);
                String prevBoardStation = transitLayer.parentStationIdForStop.get(prevBoardStopIndex);
                String prevBoardStopZoneId = transitLayer.fareZoneForStop.get(prevBoardStopIndex);

                // alight stop for this ride
                int prevAlightStopIndex = alightStops.get(ride - 1);
                String prevAlightStopZoneId = transitLayer.fareZoneForStop.get(prevAlightStopIndex);

                Fare prevFare = fares.getFareOrDefault(getRouteId(prevRoute), prevBoardStopZoneId, prevAlightStopZoneId);
                TransferRuleGroup previous = fareGroups.get(prevFare.fare_id);

                // servicesConnectedBehindFareGates contains an implicit bounds check that ride >= 1
                // this is actually not right, as issuing service could be bus with a free transfer to subway
                // Previous is not always the same as issuing, e.g. when there was a previous bus -> subway transfer
                if (servicesConnectedBehindFareGates(previous, receiving)) {
                    int fromStopIndex = alightStops.get(ride - 1);
                    String fromStation = transitLayer.parentStationIdForStop.get(fromStopIndex);
                    // if the previous alighting stop and this boarding stop are connected behind fare
                    // gates (and without riding a vehicle!), continue to the next ride. There is no CharlieCard tap
                    // and thus for fare purposes these are a single ride.
                    if (platformsConnected(fromStopIndex, fromStation, boardStopIndex, boardStation)) continue;
                }
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
        // After a transfer to the destination (state.stop == -1) you are by defintion outside the subway.
        if (patterns.size() > 0 && state.stop != -1) {
            int prevPattern = patterns.get(patterns.size() - 1);
            RouteInfo prevRoute = transitLayer.routes.get(transitLayer.tripPatterns.get(prevPattern).routeIndex);

            // board stop for this ride
            int prevBoardStopIndex = boardStops.get(boardStops.size() - 1);
            String prevBoardStopZoneId = transitLayer.fareZoneForStop.get(prevBoardStopIndex);

            // alight stop for this ride
            int prevAlightStopIndex = alightStops.get(alightStops.size() - 1);
            String prevAlightStation = transitLayer.parentStationIdForStop.get(prevAlightStopIndex);
            String prevAlightStopZoneId = transitLayer.fareZoneForStop.get(prevAlightStopIndex);

            Fare prevFare = fares.getFareOrDefault(getRouteId(prevRoute), prevBoardStopZoneId, prevAlightStopZoneId);
            TransferRuleGroup previous = fareGroups.get(prevFare.fare_id);

            if (modesWithBehindGateTransfers.contains(previous)) {
                // it is possible that we are inside fare gates, because the last vehicle we rode would have left us there
                String currentStation = transitLayer.parentStationIdForStop.get(state.stop);
                boolean behindGates = platformsConnected(prevAlightStopIndex, prevAlightStation, state.stop, currentStation);
                transferAllowance = transferAllowance.setBehindGates(behindGates);
            } else {
                transferAllowance = transferAllowance.setBehindGates(false);
            }
        } else {
            transferAllowance = transferAllowance.setBehindGates(false);
        }

        // if we ended up behind gates in the subway, we can get a free transfer to the subway. This is not neeed in
        // fare calculation but is important in dominance. In fact, doing this would cause a problem in fare calculation,
        // because payDifference uses the value field of the transfer allowance under construction. But once we return
        // the transfer allowance, payDifference is no longer used.
        // This is important for the silver line, which can get you behind gates for less than 2.25.
        int subwayFare = (int) Math.round(fares.byId.get(SUBWAY_FARE_ID).fare_attribute.price * 100);
        if (transferAllowance.behindGates && transferAllowance.value < subwayFare) {
            transferAllowance = new BostonTransferAllowance(subwayFare, transferAllowance.number, transferAllowance.expirationTime,
                    transferAllowance.transferRuleGroup, transferAllowance.behindGates);
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