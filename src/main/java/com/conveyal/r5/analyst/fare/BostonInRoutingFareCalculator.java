package com.conveyal.r5.analyst.fare;

import com.conveyal.gtfs.model.Fare;
import com.conveyal.gtfs.model.Stop;
import com.conveyal.r5.profile.McRaptorSuboptimalPathProfileRouter;
import com.conveyal.r5.transit.RouteInfo;
import com.conveyal.r5.transit.TransitLayer;
import gnu.trove.list.TIntList;
import gnu.trove.list.array.TIntArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Greedy fare calculator for the MBTA, assuming use of CharlieCard
 */
public class BostonInRoutingFareCalculator extends InRoutingFareCalculator {

    private static final WeakHashMap<TransitLayer, FareSystemWrapper> fareSystemCache = new WeakHashMap<>();

    // Fares for bus and rapid transit
    private RouteBasedFareSystem fares;

    // All routes with route_type 2 use the same Commuter Rail system of zones except FIXME CapeFlyer and Foxboro
    // We implement this as a separate fare system to avoid having to enumerate all origin-destination-route
    // combinations for commuter rail in our input data.
    private ZoneBasedFareSystem commuterRailFares = new ZoneBasedFareSystem();

    private static final String LOCAL_BUS = "localBus";
    private static final String INNER_EXPRESS_BUS = "innerExpressBus";
    private static final String OUTER_EXPRESS_BUS = "outerExpressBus";
    private static final String SUBWAY = "subway";

    private static final Set<List<String>> transferEligibleFareSequences = new HashSet<>(
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
    private static final Set<Set<String>> stationsConnectedBehindGates = new HashSet<>(Arrays.asList(
            new HashSet<>(Arrays.asList("place-dwnxg", "park"))));

    private static final Logger LOG = LoggerFactory.getLogger(BostonInRoutingFareCalculator.class);

    public class BostonTransferPrivilege extends TransferPrivilege {

        public BostonTransferPrivilege(Fare fare, int startTime){
            super(fare, priceToInt(Math.min(fares.byId.get(SUBWAY).fare_attribute.price, fare.fare_attribute
                    .price)), startTime);
        }

        @Override
        public boolean canTransferPrivilegeDominate(TransferPrivilege other) {
            return super.canTransferPrivilegeDominate(other) && (
                // neither or both TransferPrivileges are coming from Express bus
                (INNER_EXPRESS_BUS.equals(this.fareId) || OUTER_EXPRESS_BUS.equals(this.fareId)) ==
                (INNER_EXPRESS_BUS.equals(other.fareId) || OUTER_EXPRESS_BUS.equals(other.fareId))
            );
        }
    }

    private static final TransferPrivilege noTransferPrivilege = new TransferPrivilege();

    private static int priceToInt (double price){
        return (int) (price * 100); // usd to cents
    }

    private static int payFullFare(Fare fare){
        return priceToInt(fare.fare_attribute.price);
    }

    private TransferPrivilege updateTransferPrivilege(TransferPrivilege transferPrivilege, Fare fare, int clockTime){
        if(fare.fare_attribute.transfers > 0){
            // if the boarding includes transfer privileges, set the values needed to use them in subsequent
            // journeyStages
            return new BostonTransferPrivilege(fare, clockTime);
        } else {
            //otherwise return the previous transfer privilege;
            return transferPrivilege;
        }
    }

    private static class FareSystemWrapper{
        public RouteBasedFareSystem fares;
        public ZoneBasedFareSystem commuterRailFares;

        public FareSystemWrapper(RouteBasedFareSystem fares, ZoneBasedFareSystem commuterRailFares) {
            this.fares = fares;
            this.commuterRailFares = commuterRailFares;
        }
    }

    public static FareSystemWrapper loadFaresFromGTFS(TransitLayer transitLayer){ //TODO actually call
        RouteBasedFareSystem fares = new RouteBasedFareSystem();
        ZoneBasedFareSystem commuterRailFares = new ZoneBasedFareSystem();

        // iterate through data from fare_rules.txt to record fare rules.
        transitLayer.fares.values().forEach(fare -> {
            fare.fare_rules.forEach(fareRule -> {
                String route_id = fareRule.route_id;
                String start_zone_id = fareRule.origin_id;
                String end_zone_id = fareRule.destination_id;
                if (!route_id.isEmpty()){
                    // route-based fares, the default for bus and rapid transit
                    fares.addFare(route_id, start_zone_id, end_zone_id, fare);
                } else {
                    // zone-based fares for commuter rail
                    commuterRailFares.addZonePair(start_zone_id, end_zone_id, priceToInt(fare.fare_attribute.price));
                }
            });
        });

        return new FareSystemWrapper(fares, commuterRailFares);

    }

    @Override
    public FareBounds calculateFare(McRaptorSuboptimalPathProfileRouter.McRaptorState state) {

        if (commuterRailFares == null){
            synchronized (this) {
                if (commuterRailFares == null){
                    synchronized (fareSystemCache) {
                        FareSystemWrapper fareSystem = fareSystemCache.computeIfAbsent(this.transitLayer,
                                BostonInRoutingFareCalculator::loadFaresFromGTFS);
                        this.commuterRailFares = fareSystem.commuterRailFares;
                        this.fares = fareSystem.fares;
                    }
                }
            }
        }

        int cumulativeFarePaid = 0;
        TransferPrivilege transferPrivilege = noTransferPrivilege;

        // extract the relevant rides
        TIntList patterns = new TIntArrayList();
        TIntList boardStops = new TIntArrayList();
        TIntList alightStops = new TIntArrayList();
        TIntList times = new TIntArrayList();

        List<String> routeNames = new ArrayList();

        while (state != null) {
            if (state.pattern == -1) continue; // on the street, not on transit
            patterns.add(state.pattern);
            alightStops.add(state.stop);
            boardStops.add(transitLayer.tripPatterns.get(state.pattern).stops[state.boardStopPosition]);
            times.add(state.time);
            state = state.back;
        }

        patterns.reverse();
        alightStops.reverse();
        boardStops.reverse();
        times.reverse();

        for (int journeyStage = 0; journeyStage < patterns.size(); journeyStage ++) {
            int pattern = patterns.get(journeyStage);
            RouteInfo route = transitLayer.routes.get(transitLayer.tripPatterns.get(pattern).routeIndex);
            Stop boardStop = transitLayer.stopForIndex.get(boardStops.get(journeyStage));
            Stop alightStop = transitLayer.stopForIndex.get(alightStops.get(journeyStage));
            int clockTime = times.get(journeyStage);

            routeNames.add(route.route_short_name != null && !route.route_short_name.isEmpty() ?
                    route.route_short_name : route.route_long_name);

            if (route.route_type == 2) { // Commuter Rail, which is zone-based and doesn't allow transfers.
                cumulativeFarePaid += commuterRailFares.getFare(boardStop.zone_id, alightStop.zone_id);
                // Officially, no free transfers to/from commuter rail, so you might expect us to set
                // farePrivilegeFromPreviousStage = 0. But if you don't use a CharlieCard for Commuter Rail, the
                // third leg of Bus - Commuter Rail - Bus can be paid with transferValue.
                //TODO Silver Line at airport
            } else {
                Fare fare = fares.byId.get(DEFAULT_FARE_ID);
                // If the route_id is explicitly associated with a Fare, reset fare to use it.
                RouteBasedFareSystem.FareKey fareKey = new RouteBasedFareSystem.FareKey(route.route_id, "","");
                if (fares.byRouteKey.containsKey(fareKey)) fare = fares.byRouteKey.get(fareKey);

                // Check for transferValue expiration
                if (transferPrivilege.hasExpiredAt(times.get(journeyStage))) transferPrivilege = noTransferPrivilege;

                boolean tryToRedeemTransfer = transferEligibleFareSequences.contains(
                        Arrays.asList(transferPrivilege.fareId, fare.fare_id))
                        && transferPrivilege.valueLimit > 0
                        && transferPrivilege.numberLimit != 0;

                // If the fare for this boarding accepts transfers and transferValue is available, attempt to use it.
                if (tryToRedeemTransfer) {
                    // Handle special cases first

                    // Negative numberLimit signals a special case
                    if (transferPrivilege.numberLimit < 0){
                        if (transferPrivilege.numberLimit == -1) { // previously we did (local bus - subway)
                            if (LOCAL_BUS.equals(fare.fare_id)){ // (local bus -> subway -> bus)
                                //Don't increment cumulativeFarePaid, just clear transferPrivilege.
                                transferPrivilege = noTransferPrivilege;
                                continue;
                            } else { // (local bus -> subway -> anything other than local bus) requires full fare on
                                // third boarding
                                cumulativeFarePaid += payFullFare(fare);
                                transferPrivilege = updateTransferPrivilege(transferPrivilege, fare, clockTime);
                            }
                        } else {
                            throw new UnsupportedOperationException("Negative transfer value!");
                        }
                    }

                    // If this transfer is to a subway route...
                    if (SUBWAY.equals(fare.fare_id)) {
                        // and if this transfer is from a subway route...
                        if (SUBWAY.equals(transferPrivilege.fareId)) {
                            Stop previousAlightStop = transitLayer.stopForIndex.get(alightStops.get(journeyStage - 1));
                            // and if the previous alighting stop and this boarding stop are connected behind fare
                            // gates (i.e. are the same stop, are within the same station where all transfers are
                            // free, or are in stations that are connected and have all transfers free, continue to the
                            // next journeyStage.
                            if (previousAlightStop == boardStop) continue;

                            String station = boardStop.parent_station;
                            String previousStation = previousAlightStop.parent_station;

                            if (station.equals(previousStation) && !stationsWithoutBehindGateTransfers.contains
                                    (station)) continue;

                            if (stationsConnectedBehindGates.contains(new HashSet<>(Arrays.asList(station,
                                    previousStation)))) continue;

                        } else if (LOCAL_BUS.equals(transferPrivilege.fareId)) { // from a local bus route
                            cumulativeFarePaid += transferPrivilege.redeemTransferValue(priceToInt(fare.fare_attribute
                                    .price));
                            transferPrivilege = new TransferPrivilege(LOCAL_BUS,170, -1, clockTime); // use -1 to
                            // signal
                            // local bus ->
                            // subway
                            // transfer, to handle special case of local bus -> subway -> local bus
                            continue;
                        }
                    }
                    // If we are not facing one of the special cases above, redeem the transfer.
                    cumulativeFarePaid += transferPrivilege.redeemTransferValue(priceToInt(fare.fare_attribute.price));
                    transferPrivilege = noTransferPrivilege;
                } else { // don't try to use transferValue, just pay the full fare for this journeyStage
                    cumulativeFarePaid += payFullFare(fare);
                    transferPrivilege = updateTransferPrivilege(transferPrivilege, fare, clockTime);
                }
            }
        }

        // warning: reams of log output
        LOG.info("Fare for {}: ${}", String.join(" -> ", routeNames), String.format("%.2f", cumulativeFarePaid / 100D));

        return new FareBounds(cumulativeFarePaid, transferPrivilege.clean());
    }

    @Override
    public String getType() {
        return "boston";
    }
}
