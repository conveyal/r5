package com.conveyal.r5.analyst.fare;

import com.conveyal.gtfs.model.Fare;
import com.conveyal.gtfs.model.Stop;
import com.conveyal.r5.profile.McRaptorSuboptimalPathProfileRouter;
import com.conveyal.r5.transit.RouteInfo;
import gnu.trove.list.TIntList;
import gnu.trove.list.array.TIntArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Greedy fare calculator for the MBTA, assuming use of CharlieCard
 */
public class BostonInRoutingFareCalculator extends InRoutingFareCalculator {

    // All routes with route_type 2 use the same Commuter Rail system of zones except FIXME CapeFlyer and Foxboro
    private ZonalFareSystem commuterRailFareSystem = new ZonalFareSystem();

    // Map from route_id values to Fare objects from gtfs-lib.  Per GTFS spec, fare_rules.txt can have multiple rows
    // with the same route (e.g. in a mixed route/zone fare system), so this should be a MultiMap in the general case.
    // In the Boston case, however, each non-commuter-rail route should be associated with only one fare.
    private Map<String, Fare> faresByRoute = new HashMap<>();
    private Map<String, Fare> faresById = new HashMap<>();

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

    private TransferPrivilege transferPrivilege;

    private static final String DEFAULT_FARE_ID = LOCAL_BUS;
    private static final Set<String> stationsWithoutBehindGateTransfers = new HashSet<>(Arrays.asList("place-coecl", "place-aport"));
    private static final Set<Set<String>> stationsConnectedBehindGates = new HashSet<>( Arrays.asList(
            new HashSet<>(Arrays.asList("place-dwnxg", "park"))));

    private static final Logger LOG = LoggerFactory.getLogger(BostonInRoutingFareCalculator.class);

    public class BostonTransferPrivilege extends TransferPrivilege {

        @Override
        public boolean isTransferPrivilegeComparableTo(TransferPrivilege other) {
            if (!(INNER_EXPRESS_BUS.equals(this.fareId) || OUTER_EXPRESS_BUS.equals(this.fareId)) &&
                    !(INNER_EXPRESS_BUS.equals(other.fareId) || OUTER_EXPRESS_BUS.equals(other.fareId))){
                // neither TransferPrivilege is coming from Express bus
                return true;
            } else if ((INNER_EXPRESS_BUS.equals(this.fareId) || OUTER_EXPRESS_BUS.equals(this.fareId)) &&
                    (INNER_EXPRESS_BUS.equals(other.fareId) || OUTER_EXPRESS_BUS.equals(other.fareId))){
                //both TransferPrivileges are coming from Express bus
                return true;
            } else {
                return false;
            }
        }
    }

    private int priceToInt (double price){
        return (int) (price * 100); // usd to cents
    }

    @Override
    public void loadFaresFromGTFS(){ //TODO actually call
        // iterate through data from fare_rules.txt to record fare rules.
        gtfsFares.values().forEach(fare -> {
            fare.fare_rules.forEach(fareRule -> {
                String route_id = fareRule.route_id;
                String start_zone_id = fareRule.origin_id;
                String end_zone_id = fareRule.destination_id;
                if (!route_id.isEmpty() && end_zone_id.isEmpty()){
                    // route-based fares, the default for bus and rapid transit
                    faresByRoute.put(route_id, fare);
                    faresById.put(fare.fare_id, fare);
                } else if (route_id.isEmpty() && !start_zone_id.isEmpty()){
                    // zone-based fares for commuter rail
                    commuterRailFareSystem.addZonePair(start_zone_id, end_zone_id, priceToInt(fare.fare_attribute.price));
                } else {
                    throw new UnsupportedOperationException("We don't yet support rows of fare_rules where both " +
                            "route_id and origin_id/destination_id are specified");
                }
            });
        });
        transferPrivilege = new BostonTransferPrivilege();
        transferPrivilege.setMaxValue(priceToInt(faresById.get(SUBWAY).fare_attribute.price));
    }

    @Override
    public FareBounds calculateFare(McRaptorSuboptimalPathProfileRouter.McRaptorState state) {
        int cumulativeFarePaid = 0;

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

            routeNames.add(route.route_short_name != null && !route.route_short_name.isEmpty() ?
                    route.route_short_name : route.route_long_name);

            if (route.route_type == 2) { // Commuter Rail, which is zone-based and doesn't allow transfers.
                cumulativeFarePaid += commuterRailFareSystem.getFare(boardStop.zone_id, alightStop.zone_id);
                // Officially, no free transfers to/from commuter rail, so you might expect us to set
                // farePrivilegeFromPreviousStage = 0. But if you don't use a CharlieCard for Commuter Rail, the
                // third leg of Bus - Commuter Rail - Bus can be paid with transferValue.
                //TODO Silver Line at airport
            } else {
                Fare fare = faresById.get(DEFAULT_FARE_ID);
                // If the route_id is explicitly associated with a Fare, reset fare to use it.
                if (faresByRoute.containsKey(route.route_id)) fare = faresByRoute.get(route.route_id);

                // Check for transferValue expiration
                transferPrivilege.checkExpiration(times.get(journeyStage));

                boolean tryToUseTransfer = transferEligibleFareSequences.contains(
                        Arrays.asList(transferPrivilege.fareId, fare.fare_id)) && transferPrivilege.val() != 0;

                // If the fare for this boarding accepts transfers and transferValue is available, attempt to use it.
                if (tryToUseTransfer) {
                    // Handle special cases first

                    // Value to signal special cases
                    if (transferPrivilege.val() < 0){
                        if (transferPrivilege.val() == -1) {
                            if (LOCAL_BUS.equals(fare.fare_id)){ // local bus - subway - bus
                                //Don't increment cumulativeFarePaid, just clear transferPrivilege.
                                transferPrivilege.clear();
                                continue;
                            } else {
                                // TODO pay full fare
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
                            cumulativeFarePaid += transferPrivilege.redeemTransfer(priceToInt(fare.fare_attribute.price));
                            transferPrivilege.set(-1, fare.fare_id); // use -1 to signal local bus -> subway
                            // transfer, to handle special case of local bus -> subway -> local bus FIXME this is ugly
                            continue;
                        }
                    }

                    // If we've gotten to this point, we have a standard transfer to redeem.
                    cumulativeFarePaid += transferPrivilege.redeemTransfer(priceToInt(fare.fare_attribute.price));
                    transferPrivilege.clear();
                } else { // pay the full fare for this journeyStage
                    int farePaidToRide = priceToInt(fare.fare_attribute.price);
                    cumulativeFarePaid += farePaidToRide;
                    // if the boarding includes transfer privileges, set the values needed to use them in subsequent
                    // journeyStages
                    if(fare.fare_attribute.transfers > 0) {
                        int newExpirationTIme = times.get(journeyStage) + fare.fare_attribute.transfer_duration;
                        transferPrivilege.set(farePaidToRide,newExpirationTIme, fare.fare_id);
                    }
                }
            }
        }

        // warning: reams of log output
        LOG.info("Fare for {}: ${}", String.join(" -> ", routeNames), String.format("%.2f", cumulativeFarePaid / 100D));

        return new FareBounds(cumulativeFarePaid, transferPrivilege);
    }

    @Override
    public String getType() {
        return "boston";
    }
}
