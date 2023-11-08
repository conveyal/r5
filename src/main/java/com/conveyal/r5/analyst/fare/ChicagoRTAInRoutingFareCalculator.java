package com.conveyal.r5.analyst.fare;

import com.conveyal.gtfs.model.Fare;
import com.conveyal.r5.profile.McRaptorSuboptimalPathProfileRouter;
import com.conveyal.r5.transit.RouteInfo;
import com.conveyal.r5.transit.TransitLayer;
import gnu.trove.list.TIntList;
import gnu.trove.list.array.TIntArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Fare calculator for the Chicago regional service (CTA, Metra, PACE)
 * Assumes use of Ventra, and purchase of 1-Day Pass when it's the best option
 */
public class ChicagoRTAInRoutingFareCalculator extends InRoutingFareCalculator {
    public static final int CTA_L_FARE = 250;
    public static final int CTA_BUS_FARE = 225;
    public static final int PACE_REGULAR_FARE = 200;
    public static final int PACE_PREMIUM_FARE = 450;

    // Boarding a Pace Premium route with a CTA-Pace day pass has a surcharge
    public static final int PACE_PREMIUM_TRANSFER = 225;

    // Transfers are pay-the-difference, up to two additional boardings within two hours of first boarding
    // TODO check if the CTA imposes restrictions on transfers; for example, does subway -> bus -> subway qualify?

    public static final int SUBSEQUENT_RIDES = 2;
    public static final int TRANSFER_DURATION_SECONDS = 2 * 60 * 60;

    // Day pass provides unlimited rides, except on Pace Premium routes ($2.25 per boarding upcharge) and Metra
    public static final int CTA_PACE_DAY_PASS = 500;

    private static final Set<Set<String>> stationsConnected = new HashSet<>(Arrays.asList(
                new HashSet<>(Arrays.asList("41660", "40260", "40370")), // Lake, State/Lake, Washington
                new HashSet<>(Arrays.asList("40070", "40560", "40850")) // Jackson (Blue), Jackson (Red), HW Library
            ));

    private static boolean platformsConnected(int fromStopIndex, String fromStation, int toStopIndex, String toStation){
        return (fromStopIndex == toStopIndex ||  // same platform
                // different platforms, same station, in stations with behind-gate transfers between platforms
                (fromStation != null && fromStation.equals(toStation)) || // TODO check stationsWithoutBehindGateTransfers
                // different stations connected with a virtual transfer
                stationsConnected.contains(new HashSet<>(Arrays.asList(fromStation, toStation))));
    }

    public static class CTAPaceTransferAllowance extends TransferAllowance {
        private final boolean unlimited;

        private CTAPaceTransferAllowance (int value, int number, int expirationTime) {
            super(value, number, expirationTime);
            this.unlimited = false;
        }

        private CTAPaceTransferAllowance (int value, int number, int expirationTime, boolean unlimited) {
            super(value, number, expirationTime);
            this.unlimited = unlimited;
        }


        private CTAPaceTransferAllowance (boolean unlimited) {
            this.unlimited = unlimited;
        }

        private CTAPaceTransferAllowance redeem (int fareToBoard) {
            assert this.value + fareToBoard < CTA_PACE_DAY_PASS;
            return new CTAPaceTransferAllowance(Math.max(fareToBoard, this.value), this.number - 1, this.expirationTime);
        }


        @Override
        public CTAPaceTransferAllowance tightenExpiration (int maxClockTime) {
            // copied from TransferAllowance but need to override so that everything stays a BostonTransferAllowance
            int expirationTime = Math.min(this.expirationTime, maxClockTime);
            return new CTAPaceTransferAllowance(this.value, this.number, expirationTime, this.unlimited);
        }


    }

    // For now, there are no transfer allowances to/from Metra

    private static final Logger LOG = LoggerFactory.getLogger(ChicagoRTAInRoutingFareCalculator.class);

    private static final WeakHashMap<TransitLayer, FareSystemWrapper> fareSystemCache = new WeakHashMap<>();

    private RouteBasedFareRules fares;

    // Pace free
    private static final Set<String> paceFreeRoutes = new HashSet<>(Arrays.asList("410", "412", "475", "811", "905",
            "926"));
    private static final Set<String> pacePremiumRoutes = new HashSet<>(Arrays.asList("236", "282", "284", "755", "768", "769", "770", "771", "772", "773", "774", "775", "776", "779", "850", "851", "855"));

    private enum Agency {CTA, METRA, PACE}

    private static int priceToInt(double price) {return (int) (price * 100);} // usd to cents

    private static int payFullFare(Fare fare) {return priceToInt(fare.fare_attribute.price);}

    private static Agency getAgency (RouteInfo route) {
        switch (route.agency_id) {
            case "PACE":
                return Agency.PACE;
            case "METRA":
                return Agency.METRA;
            default:
                // CTA GTFS does not include agency_id.
                return Agency.CTA;
        }
    }
    
    @Override
    public FareBounds calculateFare(McRaptorSuboptimalPathProfileRouter.McRaptorState state, int maxClockTime) {
        // First, load fare data from GTFS
        if (fares == null) {
            synchronized (this) {
                if (fares == null) {
                    synchronized (fareSystemCache) {
                        FareSystemWrapper fareSystem = fareSystemCache.computeIfAbsent(this.transitLayer,
                                ChicagoRTAInRoutingFareCalculator::loadFaresFromGTFS);
                        this.fares = fareSystem.fares;
                    }
                }
            }
        }


        // Initialize: haven't boarded, paid a fare, or received a transfer allowance
        int cumulativeFarePaid = 0;
        CTAPaceTransferAllowance transferAllowance = new CTAPaceTransferAllowance(false);

        // Extract relevant data about rides
        TIntList patterns = new TIntArrayList();
        TIntList boardStops = new TIntArrayList();
        TIntList alightStops = new TIntArrayList();
        TIntList boardTimes = new TIntArrayList();

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

        // reverse data about the rides so that we can step forward through them
        patterns.reverse();
        alightStops.reverse();
        boardStops.reverse();
        boardTimes.reverse();

        int alightStopIndex;

        // Loop over rides to get to the state in forward-chronological order
        for (int ride = 0; ride < patterns.size(); ride++) {
            int pattern = patterns.get(ride);
            RouteInfo route = transitLayer.routes.get(transitLayer.tripPatterns.get(pattern).routeIndex);
            Agency agency = getAgency(route);

            // board stop for this ride
            int boardStopIndex = boardStops.get(ride);
            String boardStation = transitLayer.parentStationIdForStop.get(boardStopIndex);
            String boardStopZoneId = transitLayer.fareZoneForStop.get(boardStopIndex);
            int boardClockTime = boardTimes.get(ride);

            // alight stop for this ride
            alightStopIndex = alightStops.get(ride);
            String alightStopZoneId = transitLayer.fareZoneForStop.get(alightStopIndex);

            if (agency == Agency.METRA) {
                // Pay the Metra fare, but don't touch the CTA-Pace transfer allowance
                Fare fare = fares.getFareOrDefault(null, boardStopZoneId, alightStopZoneId);
                cumulativeFarePaid += payFullFare(fare);
            } else {
                if (transferAllowance.unlimited) continue;
                int fareToPay = 0;
                if (agency == Agency.PACE) {
                    String shortenedRouteId = route.route_id.split("-")[0];
                    if (paceFreeRoutes.contains(shortenedRouteId)) continue;
                    if (pacePremiumRoutes.contains(shortenedRouteId)) fareToPay = PACE_PREMIUM_FARE;
                    else fareToPay = PACE_REGULAR_FARE;
                } if (agency == Agency.CTA) {
                    if (route.route_type == 1) {
                        // Boarding metro (CTA "L" service)
                        if (boardStation.equals("40890")) { // Boarding at O'Hare; buy a day pass to cover the surcharge
                            cumulativeFarePaid += CTA_PACE_DAY_PASS - transferAllowance.value;
                            transferAllowance = new CTAPaceTransferAllowance(true);
                        }

                        if (ride > 0) {
                            // If we have already taken a ride, check whether we can do an in-system (behind fare gate)
                            // transfer
                            int fromStopIndex = alightStops.get(ride - 1);
                            String fromStation = transitLayer.parentStationIdForStop.get(fromStopIndex);
                            if (platformsConnected(fromStopIndex, fromStation, boardStopIndex, boardStation)) {
                                // Transfer behind gates, no Ventra tap or change in transfer allowance
                                continue;
                            }
                        }
                        else {
                            fareToPay = CTA_L_FARE;
                        }
                    }
                    else fareToPay = CTA_BUS_FARE;
                }
                if (transferAllowance.number > 0) {
                    // We have a transfer to redeem
                    if (fareToPay <= transferAllowance.value) {
                        // No additional fare required to board
                        // TODO handle special case of PACE_PREMIUM_TRANSFER from day pass
                        transferAllowance = transferAllowance.redeem(fareToPay);
                    } else {
                        // Additional fare required (transferring to a more expensive service than previously ridden)
                        if (fareToPay + transferAllowance.value < CTA_PACE_DAY_PASS) {
                            cumulativeFarePaid += transferAllowance.payDifference(fareToPay);
                            transferAllowance = transferAllowance.redeem(fareToPay);
                        } else {
                            // Should have bought a day pass instead. We'll allow it retroactively.
                            cumulativeFarePaid += CTA_PACE_DAY_PASS - transferAllowance.value;
                            transferAllowance = new CTAPaceTransferAllowance(true);
                        }
                    }
                } else {
                    // No transfer to redeem; pay full fare and get a fresh transfer allowance.
                    cumulativeFarePaid += fareToPay;
                    transferAllowance = new CTAPaceTransferAllowance(fareToPay, SUBSEQUENT_RIDES,
                            boardClockTime + TRANSFER_DURATION_SECONDS);
                }
            }
        }
        return new FareBounds(cumulativeFarePaid, transferAllowance.tightenExpiration(maxClockTime));
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
    @Override
    public String getType() {
        return "chicago-rta";
    }
}
