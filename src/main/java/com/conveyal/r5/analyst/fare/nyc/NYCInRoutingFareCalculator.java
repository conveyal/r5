package com.conveyal.r5.analyst.fare.nyc;

import com.conveyal.r5.analyst.fare.FareBounds;
import com.conveyal.r5.analyst.fare.InRoutingFareCalculator;
import com.conveyal.r5.profile.McRaptorSuboptimalPathProfileRouter;
import com.conveyal.r5.transit.TransitLayer;
import gnu.trove.list.TIntList;
import gnu.trove.list.array.TIntArrayList;
import gnu.trove.map.TIntIntMap;
import gnu.trove.map.TIntObjectMap;
import gnu.trove.map.TObjectIntMap;
import gnu.trove.map.hash.TIntIntHashMap;
import gnu.trove.map.hash.TIntObjectHashMap;
import gnu.trove.map.hash.TObjectIntHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * An in-routing fare calculator for East-of-Hudson services in the NYC area, used for a specific project with
 * customized inputs. See note in NYCStaticFareData.
 *
 * @author mattwigway
 */

public class NYCInRoutingFareCalculator extends InRoutingFareCalculator {
    private static final Logger LOG = LoggerFactory.getLogger(NYCInRoutingFareCalculator.class);

    /**
     * weak hash map from transit layer to cached NYC-specific fare data about that transit layer,
     * weak so that it doesn't prevent GC of no longer used transport networks (though this is largely hypothetical,
     * as Analysis workers never load more than a single transport network.
     */
    private static final Map<TransitLayer, NYCFareDataCache> fareDataForTransitLayer = new WeakHashMap<>();

    /** Map from NYCPatternType -> Integer for how much discount off the LIRR is implied by each MetroCard Transfer Source */
    public Map<NYCPatternType, Integer> toLirrDiscounts = null;

    /** Map from NYCPatternType -> Integer for how much discount off the MNR is implied by each MetroCard Transfer Source */
    public Map<NYCPatternType, Integer> toMetroNorthDiscounts = null;

    /**
     * What type of MetroCard Transfer Allowance does riding the LIRR get you?
     * This is used in scenarios, we can make a full-fare LIRR ride give you the same transfer allowance
     * as some other type of ride that is already implemented, e.g. NICE_ONE_TRANSFER for a single free
     * transfer to NICE, or METROCARD_LOCAL_BUS for free transfers to most services and an upgrade to the express bus.
     * No LIRR MetroCard Transfer Allowance is granted if a discounted transfer was used to board the LIRR.
     */
    public NYCPatternType lirrMetrocardTransferSource = null;

    /** Similar, for the MNR */
    public NYCPatternType metroNorthMetrocardTransferSource = null;

    /** Override certain fares for the LIRR */
    public List<FareOverride> lirrFareOverrides = null;

    /** Override certain fares for the MNR */
    public List<FareOverride> metroNorthFareOverrides = null;

    /** Map from stop -> stop -> fare for fare overrides coming from JSON, for Metro-North peak fares */
    private TIntObjectMap<TIntIntMap> mnrPeakFareOverrides = null;

    /** Map from stop -> stop -> fare for fare overrides coming from JSON, for Metro-North offpeak fares */
    private TIntObjectMap<TIntIntMap> mnrOffPeakFareOverrides = null;

    // LIRR ones package-private so they can be accessed from LIRRTransferAllowance
    /** Map from stop -> stop -> fare for fare overrides coming from JSON, for LIRR peak direct fares */
    Map<LIRRStop, TObjectIntMap<LIRRStop>> lirrPeakDirectFareOverrides = null;

    /** Map from stop -> stop -> fare for fare overrides coming from JSON, for LIRR offpeak direct fares */
    Map<LIRRStop, TObjectIntMap<LIRRStop>> lirrOffPeakDirectFareOverrides = null;

    /** Map for from stop -> to stop -> via stop -> fare for LIRR peak via fare overrides coming from JSON */
    Map<LIRRStop, Map<LIRRStop, TObjectIntMap<LIRRStop>>> lirrPeakViaFareOverrides = null;

    /** Map for from stop -> to stop -> via stop -> fare for LIRR offpeak via fare overrides coming from JSON */
    Map<LIRRStop, Map<LIRRStop, TObjectIntMap<LIRRStop>>> lirrOffPeakViaFareOverrides = null;

    /** Create the cached fare data iff there is a cache mix, otherwise just return it */
    private NYCFareDataCache getOrCreateFareData () {
        if (!fareDataForTransitLayer.containsKey(transitLayer)) {
            synchronized (fareDataForTransitLayer) {
                if (!fareDataForTransitLayer.containsKey(transitLayer)) {
                    LOG.info("Initializing NYC InRoutingFareCalculator");
                    NYCFareDataCache fareData = new NYCFareDataCache(this.transitLayer);
                    fareDataForTransitLayer.put(transitLayer, fareData);
                }
            }
        }

        return fareDataForTransitLayer.get(transitLayer);
    }

    /** Initialize the fare overrides for direct trips on Metro-North */
    private TIntObjectMap<TIntIntMap> initializeMnrDirectFareOverrides (List<FareOverride> fareOverrides, boolean peak) {
        TIntObjectMap<TIntIntMap> fares = new TIntObjectHashMap<>();
        if (fareOverrides != null) {
            for (FareOverride fo : fareOverrides) {
                // find the stops in the transit layer
                if (fo.viaStopId != null)
                    throw new IllegalArgumentException("MNR override fare cannot have via stop!");

                if (!transitLayer.indexForStopId.containsKey(fo.fromStopId) ||
                        !transitLayer.indexForStopId.containsKey(fo.toStopId)) {
                    throw new IllegalArgumentException("from or to stop not found!");
                }

                int fromStop = transitLayer.indexForStopId.get(fo.fromStopId);
                int toStop = transitLayer.indexForStopId.get(fo.toStopId);
                int fare = peak ? fo.peakFare : fo.offPeakFare;
                if (!fares.containsKey(fromStop))
                    fares.put(fromStop, new TIntIntHashMap());
                fares.get(fromStop).put(toStop, fare);
            }
        }
        return fares;
    }

    /**
     * Initialize the fare overrides for direct trips on the LIRR. Can't re-use Metro-North code b/c it used int stop IDs and LIRR fare calculation uses enums
     */
    private Map<LIRRStop, TObjectIntMap<LIRRStop>> initializeLirrDirectFareOverrides (List<FareOverride> fareOverrides, boolean peak, NYCFareDataCache fareData) {
        Map<LIRRStop, TObjectIntMap<LIRRStop>> fares = new HashMap<>();
        if (fareOverrides != null) {
            for (FareOverride fo :  fareOverrides) {
                if (fo.viaStopId != null) continue;

                if (!transitLayer.indexForStopId.containsKey(fo.fromStopId) ||
                        !transitLayer.indexForStopId.containsKey(fo.toStopId)) {
                    throw new IllegalArgumentException("from or to stop not found!");
                }

                int fromStop = transitLayer.indexForStopId.get(fo.fromStopId);
                int toStop = transitLayer.indexForStopId.get(fo.toStopId);
                int fare = peak ? fo.peakFare : fo.offPeakFare;
                LIRRStop fromLirrStop = fareData.lirrStopForTransitLayerStop.get(fromStop);
                LIRRStop toLirrStop = fareData.lirrStopForTransitLayerStop.get(toStop);

                fares.computeIfAbsent(fromLirrStop, k -> new TObjectIntHashMap<>()).put(toLirrStop, fare);
            }
        }
        return fares;
    }

    /** Initialize the fare overrides for via trips on the LIRR - returns a map keyed as from -> to -> via -> fare */
    private Map<LIRRStop, Map<LIRRStop, TObjectIntMap<LIRRStop>>> initializeLirrViaFareOverrides (List<FareOverride> fareOverrides, boolean peak, NYCFareDataCache fareData) {
        Map<LIRRStop, Map<LIRRStop, TObjectIntMap<LIRRStop>>> fares = new HashMap<>();
        if (fareOverrides != null) {
            for (FareOverride fo :  fareOverrides) {
                if (fo.viaStopId == null) continue;

                if (!transitLayer.indexForStopId.containsKey(fo.fromStopId) ||
                        !transitLayer.indexForStopId.containsKey(fo.toStopId) ||
                        !transitLayer.indexForStopId.containsKey(fo.viaStopId)) {
                    throw new IllegalArgumentException("from or to stop not found!");
                }

                int fromStop = transitLayer.indexForStopId.get(fo.fromStopId);
                int toStop = transitLayer.indexForStopId.get(fo.toStopId);
                int viaStop = transitLayer.indexForStopId.get(fo.viaStopId);
                int fare = peak ? fo.peakFare : fo.offPeakFare;
                LIRRStop fromLirrStop = fareData.lirrStopForTransitLayerStop.get(fromStop);
                LIRRStop toLirrStop = fareData.lirrStopForTransitLayerStop.get(toStop);
                LIRRStop viaLirrStop = fareData.lirrStopForTransitLayerStop.get(viaStop);


                fares.computeIfAbsent(fromLirrStop, k -> new HashMap<>())
                        .computeIfAbsent(toLirrStop, k -> new TObjectIntHashMap<>())
                        .put(viaLirrStop, fare);
            }
        }
        return fares;
    }

    private void initializeFareOverrides (NYCFareDataCache fareData) {
        if (mnrPeakFareOverrides == null) {
            synchronized (this) {
                if (mnrPeakFareOverrides == null) {
                    mnrPeakFareOverrides = initializeMnrDirectFareOverrides(metroNorthFareOverrides, true);
                    mnrOffPeakFareOverrides = initializeMnrDirectFareOverrides(metroNorthFareOverrides, false);

                    lirrPeakDirectFareOverrides = initializeLirrDirectFareOverrides(lirrFareOverrides, true, fareData);
                    lirrOffPeakDirectFareOverrides = initializeLirrDirectFareOverrides(lirrFareOverrides, false, fareData);

                    lirrPeakViaFareOverrides = initializeLirrViaFareOverrides(lirrFareOverrides, true, fareData);
                    lirrOffPeakViaFareOverrides = initializeLirrViaFareOverrides(lirrFareOverrides, false, fareData);
                }
            }
        }
    }

    @Override
    public FareBounds calculateFare(McRaptorSuboptimalPathProfileRouter.McRaptorState state, int maxClockTime) {
        NYCFareDataCache fareData = getOrCreateFareData();
        initializeFareOverrides(fareData);

        // Extract relevant data about rides
        TIntList patterns = new TIntArrayList();
        TIntList boardStops = new TIntArrayList();
        TIntList alightStops = new TIntArrayList();
        TIntList boardTimes = new TIntArrayList();
        // not using bitset b/c hard to reverse
        List<Boolean> reachedViaOnStreetTransfer = new ArrayList<>();

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

            // last condition is to avoid using loop transfer to throw away a negative Metro-North or LIRR transfer allowance
            // check if back is an on-street transfer
            boolean onStreetTransfer = stateForTraversal.back != null && stateForTraversal.back.back != null &&
                    stateForTraversal.back.pattern == -1 && stateForTraversal.back.stop != stateForTraversal.back.back.stop;
            reachedViaOnStreetTransfer.add(onStreetTransfer);

            stateForTraversal = stateForTraversal.back;
        }

        // reverse data about the rides so we can step forward through them
        patterns.reverse();
        alightStops.reverse();
        boardStops.reverse();
        boardTimes.reverse();
        Collections.reverse(reachedViaOnStreetTransfer);

        // TODO put all of these things into objects to make clearing less error-prone
        // metroNorthState = null instead of clearing 5 fields.
        List<LIRRStop> lirrBoardStops = null;
        List<LIRRStop> lirrAlightStops = null;
        List<LIRRTransferAllowance.LIRRDirection> lirrDirections = null;
        BitSet lirrPeak = new BitSet();
        TIntList lirrBoardTimes = new TIntArrayList();
        int lirrRideIndex = 0;

        int cumulativeFare = 0;
        LIRRTransferAllowance lirr = null;

        // Metrocard fare information: where the fare came from (i.e. what mode was paid for), as this determines what
        // you can transfer to.
        NYCPatternType metrocardTransferSource = null;
        int metrocardTransferExpiry = maxClockTime;
        boolean inSubwayPaidArea = false; // when inside the subway paid area, we can change to a new subway line for free

        // Metro-North transfer information
        int metroNorthBoardStop = -1;
        int metroNorthAlightStop = -1;
        boolean metroNorthPeak = false;
        // keep track of whether we're on the NH line or a different one, since there are no free
        // transfers from the New Haven line to the Harlem/Hudson lines, at least not with the one-way tickets
        // http://www.iridetheharlemline.com/2010/09/22/question-of-the-day-can-i-use-my-ticket-on-other-lines/
        MetroNorthLine metroNorthLine = null;
        int metroNorthDirection = -1;
        int metroNorthBoardTime = -1;

        // ==========================================
        // ======= MAIN FARE CALCULATION LOOP =======
        // ==========================================
        // run one extra iteration, so the cleanup code to pay LIRR and MNR fares and check for subway exit
        // can get run after the last ride so transfer allowances are correct.
        for (int i = 0; i < patterns.size() + 1; i++) {
            int pattern, boardStop, alightStop, boardTime, previousAlightStop;
            boolean onStreetTransfer;
            boolean lastIteration;
            NYCPatternType patternType;

            if (i < patterns.size()) {
                pattern = patterns.get(i);
                boardStop = boardStops.get(i);
                alightStop = alightStops.get(i);
                boardTime = boardTimes.get(i);
                onStreetTransfer = reachedViaOnStreetTransfer.get(i);
                patternType = fareData.patternTypeForPattern[pattern];
                lastIteration = false;
            } else {
                // last iteration
                pattern = boardStop = alightStop = boardTime = -1;
                onStreetTransfer = false; // not used
                patternType = null;
                lastIteration = true;
            }

            if (i > 0) {
                // Don't do this at the end of the loop since there is short-circuit logic (continue) below
                previousAlightStop = alightStops.get(i - 1);
            } else {
                previousAlightStop = -1;
            }

            // I. CLEAN UP AFTER COMMUTER RAIL RIDE ====================================================================
            // I.A. PAY FARE UPON LEAVING THE LIRR
            boolean thisPatternLirr = NYCPatternType.LIRR_OFFPEAK.equals(patternType) ||
                    NYCPatternType.LIRR_PEAK.equals(patternType);
            if (lirrBoardStops != null && (onStreetTransfer || !thisPatternLirr)) {
                // we have left the LIRR, either by transferring on street or riding a different transit service.
                lirr = new LIRRTransferAllowance(lirrBoardStops, lirrAlightStops, lirrDirections, lirrBoardTimes, lirrPeak, this);
                int lirrFare = lirr.cumulativeFare;
                int lirrDiscount = getToLirrDiscountForMetrocardTransferSource(metrocardTransferSource);
                if (lirrDiscount > 0 && lirrBoardTimes.get(0) <= metrocardTransferExpiry) {
                    // We are using a transfer, clear MetroCard transfer allowance
                    metrocardTransferSource = null;
                    metrocardTransferExpiry = maxClockTime;
                    lirrFare -= lirrDiscount;
                    if (lirrFare < 0) {
                        LOG.warn("Discount on LIRR is larger than LIRR fare; assuming it's free.");
                        lirrFare = 0;
                    }
                } else {
                    // if a discount was used at boarding, we don't give a discount at alighting
                    // NB this may create issues if the transfer discounts are asymmetrical, by creating places where
                    // transfer allowances are negative. E.g. if a NICE bus gives a 1.75 discount off LIRR, and LIRR
                    // gives a 2.75 discount off local bus, riding the NICE bus before LIRR and foregoing the later discount
                    // makes the fare $1 higher than if you had discarded the NICE allowance beforehand.
                    // This also is slightly incorrect when a second ticket (rather than a via fare) was purchased for the
                    // LIRR, as in this case you would expect to get the second transfer, but that's a pretty minor edge case.
                    if (lirrMetrocardTransferSource != null) {
                        // NB this overwrites any previous MetroCard transfer information
                        // You can't carry a transfer with you on full-fare LIRR
                        metrocardTransferSource = lirrMetrocardTransferSource;
                        metrocardTransferExpiry = Math.min(lirr.mostRecentTicketPurchaseTime + NYCStaticFareData.METROCARD_TRANSFER_VALIDITY_TIME_SECONDS, maxClockTime);
                    }
                }
                cumulativeFare += lirrFare;
                if (!lastIteration) { // don't clear LIRR transfer allowance information on last iteration, save it for use in dominance
                    lirr = null;
                    lirrBoardStops = null;
                    lirrAlightStops = null;
                    lirrPeak.clear();
                    lirrRideIndex = 0;
                }
            }

            // I.B. PAY FARE FOR METRO-NORTH AFTER LEAVING
            boolean thisPatternMnr = NYCPatternType.METRO_NORTH_PEAK.equals(patternType) ||
                    NYCPatternType.METRO_NORTH_OFFPEAK.equals(patternType);
            if (metroNorthBoardStop != -1 && (onStreetTransfer || !thisPatternMnr)) {
                TIntObjectMap<TIntIntMap> overrideFares = metroNorthPeak ? mnrPeakFareOverrides : mnrOffPeakFareOverrides;
                int mnrFare;
                if (overrideFares.containsKey(metroNorthBoardStop) && overrideFares.get(metroNorthBoardStop).containsKey(metroNorthAlightStop)) {
                    mnrFare = overrideFares.get(metroNorthBoardStop).get(metroNorthAlightStop);
                } else {
                    mnrFare = fareData.getMetroNorthFare(metroNorthBoardStop, metroNorthAlightStop,
                            metroNorthPeak);
                }

                int mnrDiscount = getToMnrDiscountForMetrocardTransferSource(metrocardTransferSource);
                if (mnrDiscount > 0 && metroNorthBoardTime <= metrocardTransferExpiry) {
                    // Clear MetroCard transfer allowance
                    metrocardTransferSource = null;
                    metrocardTransferExpiry = maxClockTime;
                    mnrFare -= mnrDiscount;
                    if (mnrFare < 0) {
                        LOG.warn("Discount on MNR is larger than MNR fare; assuming it's free.");
                        mnrFare = 0;
                    }
                } else {
                    // see comment under LIRR about negative transfer allowances.
                    if (metroNorthMetrocardTransferSource != null) {
                        // NB this overwrites any previous MetroCard transfer information
                        // You can't carry a transfer with you on full-fare MNR
                        metrocardTransferSource = metroNorthMetrocardTransferSource;
                        metrocardTransferExpiry = Math.min(metroNorthBoardTime + NYCStaticFareData.METROCARD_TRANSFER_VALIDITY_TIME_SECONDS, maxClockTime);
                    }
                }
                cumulativeFare += mnrFare;
                if (!lastIteration) {
                    // Don't clear Metro-North transfer information before returning transfer allowance
                    metroNorthBoardStop = metroNorthAlightStop = metroNorthDirection = -1;
                    metroNorthPeak = false;
                    metroNorthLine = null;
                }
            }

            if (lastIteration) break; // Clean up code from previous rides is done

            // II. PREPARE FOR THIS RIDE ===============================================================================

            // II.A. CLEAR NICE TRANSFER ALLOWANCE IF WE ARE GOING TO RIDE A DIFFERENT SERVICE
            // All NICE routes have a list of allowable transfers, e.g. see the lower left corner of this schedule:
            // http://www.nicebus.com/NICE/media/NiceBusPDFSchedules/NICE-n31_MapSchedule.pdf
            // Implementing these is too complicated, so we just assume that any transfer that can be made on
            // foot is allowable. But clear the transfer allowance if another service, such as Long Island Rail Road,
            // that doesn't use the MetroCard transfer allowance system, is ridden.
            if (NYCPatternType.METROCARD_NICE.equals(metrocardTransferSource) || NYCPatternType.METROCARD_NICE_ONE_TRANSFER.equals(metrocardTransferSource)) {
                if (!(NYCPatternType.niceTransfers.contains(patternType) ||
                        (getToLirrDiscountForMetrocardTransferSource(metrocardTransferSource) > 0 &&
                                (NYCPatternType.LIRR_PEAK.equals(patternType) || NYCPatternType.LIRR_OFFPEAK.equals(patternType))) ||
                        (getToMnrDiscountForMetrocardTransferSource(metrocardTransferSource) > 0 &&
                                (NYCPatternType.METRO_NORTH_PEAK.equals(patternType) || NYCPatternType.METRO_NORTH_OFFPEAK.equals(patternType))))) {
                    // we are riding a service that doesn't provide a NICE discount. Clear the transfer allowance.
                    // This is correct even in the METROCARD_NICE_ONE_TRANSFER case, because if we transferred to another
                    // MetroCard service that _doesn't_ accept the NICE_ONE_TRANSFER transfer, a new ticket will be purchased and
                    // the transfer allowance cleared.
                    metrocardTransferSource = null;
                    metrocardTransferExpiry = maxClockTime;
                }
            }

            // II.B. CLEAR LOCAL BUS TO SIR TO SI FERRY IF NEXT RIDE IS NOT LOCAL BUS/SUBWAY
            // This is to sort-of enforce that you can only get a second transfer if you board near the ferry terminal.
            if (NYCPatternType.LOCAL_BUS_TO_SIR_TO_SI_FERRY.equals(metrocardTransferSource) &&
                    !NYCPatternType.METROCARD_LOCAL_BUS.equals(patternType) &&
                    !NYCPatternType.METROCARD_SUBWAY.equals(patternType)
            ) {
                metrocardTransferSource = null;
                metrocardTransferExpiry = maxClockTime;
            }

            // II.C. CLEAR LOCAL BUS TO FERRY TO SIR IF NEXT RIDE IS NOT LOCAL BUS
            // This is to sort-of enforce that you can only get a second transfer if you board near the SIR, and keep
            // the router from doubling back to lower manhattan on the ferry and transferring to a bus.
            if (NYCPatternType.LOCAL_BUS_OR_SUBWAY_TO_SI_FERRY_TO_SIR.equals(metrocardTransferSource) &&
                    !NYCPatternType.METROCARD_LOCAL_BUS.equals(patternType)
            ) {
                metrocardTransferSource = null;
                metrocardTransferExpiry = maxClockTime;
            }

            // II.D. CLEAR LOCAL BUS TO SIR IF FERRY IS NOT RIDDEN NEXT
            // shouldn't matter, as riding any MetroCard service will clear it, but just for good measure
            if (NYCPatternType.LOCAL_BUS_TO_SIR.equals(metrocardTransferSource) && !NYCPatternType.STATEN_ISLAND_FERRY.equals(patternType)) {
                // transfer allowance is consumed
                metrocardTransferSource = null;
                metrocardTransferExpiry = maxClockTime;
            }

            // No need to enforce for other options:
            // LOCAL_BUS_TO_SI_FERRY, SUBWAY_TO_SI_FERRY: Only special when boarding SIR from Ferry, otherwise behaves exactly like a local
            //   bus or subway transfer allowance. These allowances are cleared if the SI Ferry is ridden twice, to prevent the router from doubling
            //   back using the ferry to get a local bus -> SIR -> local bus ride entirely on Staten Island.

            // II.E. CHECK FOR SUBWAY SYSTEM EXIT
            // inSubwayPaidArea refers to whether we were in the subway paid area _before_ this ride at this point.
            // Elvis has left the subway
            if (inSubwayPaidArea) {
                // If we're still riding the subway, check if it was a behind-gates transfer
                if (NYCPatternType.METROCARD_SUBWAY.equals(patternType)) inSubwayPaidArea = hasBehindGatesTransfer(previousAlightStop, boardStop, fareData);
                // If we're not still riding the subway, well then, it is tautologically clear that we have
                // left the subway.
                else inSubwayPaidArea = false;
            }

            // III. PROCESS THIS RIDE ==================================================================================
            // III.A. MTA LOCAL BUS
            if (NYCPatternType.METROCARD_LOCAL_BUS.equals(patternType)) {
                if (boardTime <= metrocardTransferExpiry &&
                        // Allow transfers from all the metrocard types except METROCARD_NICE_ONE_TRANSFER and the
                        // SUFFOLK ones, which do not allow transfers to the MTA
                        (NYCPatternType.METROCARD_LOCAL_BUS.equals(metrocardTransferSource) ||
                                // Special case: one more free transfer to local bus on Staten Island
                                // (bus/subway to SI Ferry to SIR implies we are now on Staten Island)
                                NYCPatternType.LOCAL_BUS_OR_SUBWAY_TO_SI_FERRY_TO_SIR.equals(metrocardTransferSource) ||
                                // Special case: one more free transfer to local bus in Manhattan
                                // (bus to SIR to Ferry implies we are now in Manhattan)
                                NYCPatternType.LOCAL_BUS_TO_SIR_TO_SI_FERRY.equals(metrocardTransferSource) ||
                                // It's okay to not ride the SIR after the ferry
                                NYCPatternType.LOCAL_BUS_TO_SI_FERRY.equals(metrocardTransferSource) ||
                                NYCPatternType.SUBWAY_TO_SI_FERRY.equals(metrocardTransferSource) ||
                                NYCPatternType.METROCARD_EXPRESS_BUS.equals(metrocardTransferSource) ||
                                NYCPatternType.METROCARD_SUBWAY.equals(metrocardTransferSource) ||
                                NYCPatternType.METROCARD_NICE.equals(metrocardTransferSource) ||
                                NYCPatternType.STATEN_ISLAND_RWY.equals(metrocardTransferSource))) {
                    // use transfer - free transfers to local bus from any MetroCard transferable route
                    // clear transfer information
                    // Not going to implement prohibited bus-bus transfers, as they are mostly to prevent round-trips
                    // anyhow, and would cause issues with pruning.
                    metrocardTransferSource = null;
                    metrocardTransferExpiry = maxClockTime;
                } else {
                    // first ride on the local bus, buy a new ticket
                    cumulativeFare += NYCStaticFareData.LOCAL_BUS_SUBWAY_FARE;
                    metrocardTransferSource = NYCPatternType.METROCARD_LOCAL_BUS;
                    metrocardTransferExpiry = Math.min(boardTime + NYCStaticFareData.METROCARD_TRANSFER_VALIDITY_TIME_SECONDS, maxClockTime);
                }
            }

            // III.B. MTA EXPRESS BUS
            else if (NYCPatternType.METROCARD_EXPRESS_BUS.equals(patternType)) {
                if (boardTime <= metrocardTransferExpiry &&
                        // Everything except NICE_ONE_TRANSFER, the SUFFOLK types, and the special types allowing two
                        // transfers from the SIR
                        (NYCPatternType.METROCARD_LOCAL_BUS.equals(metrocardTransferSource) ||
                                NYCPatternType.METROCARD_SUBWAY.equals(metrocardTransferSource) ||
                                // It's okay to not ride the SIR after the ferry
                                NYCPatternType.LOCAL_BUS_TO_SI_FERRY.equals(metrocardTransferSource) ||
                                NYCPatternType.SUBWAY_TO_SI_FERRY.equals(metrocardTransferSource) ||
                                NYCPatternType.METROCARD_NICE.equals(metrocardTransferSource) ||
                                NYCPatternType.STATEN_ISLAND_RWY.equals(metrocardTransferSource))) {
                    // transfer from subway or local bus to express bus, pay upgrade fare and clear transfer allowance
                    cumulativeFare += NYCStaticFareData.EXPRESS_BUS_UPGRADE;
                    metrocardTransferSource = null;
                    metrocardTransferExpiry = maxClockTime;
                } else if (boardTime <= metrocardTransferExpiry &&
                        NYCPatternType.METROCARD_EXPRESS_BUS.equals(metrocardTransferSource)) {
                    // Free express bus -> express bus transfer, see http://web.mta.info/nyct/fare/pdf/NYCTransit_Tariff.pdf
                    // page 33.
                    metrocardTransferSource = null;
                    metrocardTransferExpiry = maxClockTime;
                } else {
                    // pay new fare
                    cumulativeFare += NYCStaticFareData.EXPRESS_BUS_FARE;
                    metrocardTransferSource = NYCPatternType.METROCARD_EXPRESS_BUS;
                    metrocardTransferExpiry = Math.min(boardTime + NYCStaticFareData.METROCARD_TRANSFER_VALIDITY_TIME_SECONDS, maxClockTime);
                }
            }

            // III.C. MTA SUBWAY
            else if (NYCPatternType.METROCARD_SUBWAY.equals(patternType)) {
                // if we were already in the subway fare area, continue without fare interaction
                if (inSubwayPaidArea) continue; // to next iteration of loop over rides
                if (boardTime <= metrocardTransferExpiry &&
                        // Everything except NICE_ONE_TRANSFER and the SUFFOLK types, the intermediate or
                        // Staten Island-bound Staten Island special cases and the subway
                        // (free subway transfers are only allowed within the subway paid area, and are handled above)
                        (NYCPatternType.METROCARD_LOCAL_BUS.equals(metrocardTransferSource) ||
                                // It's okay to not ride the SIR after the ferry
                                NYCPatternType.LOCAL_BUS_TO_SI_FERRY.equals(metrocardTransferSource) ||
                                // free transfer to subway after Local Bus -> SIR -> SIF
                                NYCPatternType.LOCAL_BUS_TO_SIR_TO_SI_FERRY.equals(metrocardTransferSource) ||
                                NYCPatternType.METROCARD_NICE.equals(metrocardTransferSource) ||
                                NYCPatternType.METROCARD_EXPRESS_BUS.equals(metrocardTransferSource) ||
                                NYCPatternType.STATEN_ISLAND_RWY.equals(metrocardTransferSource))) {
                    // free transfer from bus/SIR to subway, but clear transfer allowance
                    metrocardTransferSource = null;
                    metrocardTransferExpiry = maxClockTime;
                } else {
                    // pay new fare
                    cumulativeFare += NYCStaticFareData.LOCAL_BUS_SUBWAY_FARE;
                    metrocardTransferSource = NYCPatternType.METROCARD_SUBWAY;
                    metrocardTransferExpiry = Math.min(boardTime + NYCStaticFareData.METROCARD_TRANSFER_VALIDITY_TIME_SECONDS,
                            maxClockTime);
                }
                // we are now in the subway paid area, regardless of whether we boarded via a transfer or not.
                inSubwayPaidArea = true;
            }

            // III.D. STATEN ISLAND RAILWAY
            else if (NYCPatternType.STATEN_ISLAND_RWY.equals(patternType)) {
                // Fare is only paid on the SIR at St George and Tompkinsville, both for boarding and alighting.
                // First calculate the full fare, then figure out transfers
                int nFarePayments = 0;
                if (fareData.statenIslandRwyFareStops.contains(boardStop)) nFarePayments++;
                if (fareData.statenIslandRwyFareStops.contains(alightStop)) nFarePayments++;

                if (nFarePayments == 0) continue; // NO FARE INTERACTION, DO NOT UPDATE TRANSFER ALLOWANCES
                else {
                    if (boardTime <= metrocardTransferExpiry &&
                            // this is all the metrocard types except METROCARD_NICE_ONE_TRANSFER and the
                            // SUFFOLK ones, which do not allow transfers to the MTA, as well as the SIR
                            // because we assume no free SIR-SIR transfers.
                            (NYCPatternType.METROCARD_LOCAL_BUS.equals(metrocardTransferSource) ||
                                    NYCPatternType.LOCAL_BUS_TO_SI_FERRY.equals(metrocardTransferSource) ||
                                    NYCPatternType.SUBWAY_TO_SI_FERRY.equals(metrocardTransferSource) ||
                                    NYCPatternType.METROCARD_EXPRESS_BUS.equals(metrocardTransferSource) ||
                                    NYCPatternType.METROCARD_SUBWAY.equals(metrocardTransferSource) ||
                                    NYCPatternType.METROCARD_NICE.equals(metrocardTransferSource))) {
                        nFarePayments--; // one fare payment is free
                    }

                    if (nFarePayments == 0) {
                        // transfer was used and no new ticket was purchased
                        // handle the third-free-transfer cases
                        if (NYCPatternType.METROCARD_LOCAL_BUS.equals(metrocardTransferSource)) {
                            // allow one more free transfer if the ferry is ridden next
                            // don't reset expiration time
                            // NB this can only happen after riding a local bus that ends on Staten
                            // Island, because otherwise the transfer source would be LOCAL_BUS_TO_SI_FERRY
                            metrocardTransferSource = NYCPatternType.LOCAL_BUS_TO_SIR;
                        } else if (NYCPatternType.LOCAL_BUS_TO_SI_FERRY.equals(metrocardTransferSource) || NYCPatternType.SUBWAY_TO_SI_FERRY.equals(metrocardTransferSource)) {
                            // allow one more free transfer to local bus
                            metrocardTransferSource = NYCPatternType.LOCAL_BUS_OR_SUBWAY_TO_SI_FERRY_TO_SIR;
                        } else {
                            metrocardTransferSource = null;
                            metrocardTransferExpiry = maxClockTime;
                        }
                    } else {
                        // the fare was paid at least once (maybe twice)
                        cumulativeFare += nFarePayments * NYCStaticFareData.LOCAL_BUS_SUBWAY_FARE;
                        metrocardTransferSource = NYCPatternType.STATEN_ISLAND_RWY;
                        metrocardTransferExpiry = Math.min(boardTime + NYCStaticFareData.METROCARD_TRANSFER_VALIDITY_TIME_SECONDS, maxClockTime);
                    }
                }
            }

            // III.E. NICE BUS
            // Some special cases here to handle that you get two free transfers to the NICE, but only
            // one to MTA services
            else if (NYCPatternType.METROCARD_NICE.equals(patternType)) {
                if (boardTime <= metrocardTransferExpiry &&
                        // this is all the metrocard types except the special ones allowing a third transfer
                        // when the SI Ferry and SIR are involved
                        (NYCPatternType.METROCARD_LOCAL_BUS.equals(metrocardTransferSource) ||
                                // Okay to board nice after riding the ferry (though I'm not sure it's possible)
                                NYCPatternType.LOCAL_BUS_TO_SI_FERRY.equals(metrocardTransferSource) ||
                                NYCPatternType.SUBWAY_TO_SI_FERRY.equals(metrocardTransferSource) ||
                                NYCPatternType.METROCARD_EXPRESS_BUS.equals(metrocardTransferSource) ||
                                NYCPatternType.METROCARD_SUBWAY.equals(metrocardTransferSource) ||
                                NYCPatternType.METROCARD_NICE.equals(metrocardTransferSource) ||
                                NYCPatternType.METROCARD_NICE_ONE_TRANSFER.equals(metrocardTransferSource) ||
                                NYCPatternType.SUFFOLK.equals(metrocardTransferSource) ||
                                NYCPatternType.SUFFOLK_ONE_TRANSFER.equals(metrocardTransferSource) ||
                                NYCPatternType.STATEN_ISLAND_RWY.equals(metrocardTransferSource))) {
                    // use the transfer
                    if (NYCPatternType.METROCARD_NICE.equals(metrocardTransferSource)) {
                        // if we're transferring NICE -> NICE, we get one more transfer
                        metrocardTransferSource = NYCPatternType.METROCARD_NICE_ONE_TRANSFER;
                    } else {
                        // consume any other transfer type, with special logic for Suffolk upgrade fares
                        if (NYCPatternType.SUFFOLK.equals(metrocardTransferSource)) {
                            // buy the transfer slip and the upgrade to the NICE fare, don't allow further transfers
                            cumulativeFare += NYCStaticFareData.SUFFOLK_TRANSFER_SLIP +
                                    NYCStaticFareData.SUFFOLK_NICE_TRANSFER;
                        } else if (NYCPatternType.SUFFOLK_ONE_TRANSFER.equals(metrocardTransferSource)) {
                            // transfer slip already bought, upgrade to NICE fare
                            cumulativeFare += NYCStaticFareData.SUFFOLK_NICE_TRANSFER;
                        }

                        // transfer allowance is consumed in any case (including Suffolk cases)
                        // Note that NICE_ONE_TRANSFER is implicitly handled here
                        metrocardTransferSource = null;
                        metrocardTransferExpiry = maxClockTime;
                    }
                } else {
                    // pay new fare
                    cumulativeFare += NYCStaticFareData.NICE_FARE;
                    metrocardTransferSource = NYCPatternType.METROCARD_NICE;
                    metrocardTransferExpiry = Math.min(boardTime + NYCStaticFareData.METROCARD_TRANSFER_VALIDITY_TIME_SECONDS, maxClockTime);
                }
            }

            // III.F. SUFFOLK COUNTY TRANSIT
            else if (NYCPatternType.SUFFOLK.equals(patternType)) {
                boolean transferValid = boardTime <= metrocardTransferExpiry;
                if (transferValid && NYCPatternType.METROCARD_NICE.equals(metrocardTransferSource)) {
                    // free transfer from NICE, consume allowance
                    metrocardTransferSource = null;
                    metrocardTransferExpiry = maxClockTime;
                } else if (transferValid && NYCPatternType.SUFFOLK.equals(metrocardTransferSource)) {
                    // buy the transfer slip for Suffolk County Transit
                    cumulativeFare += NYCStaticFareData.SUFFOLK_TRANSFER_SLIP;
                    metrocardTransferSource = NYCPatternType.SUFFOLK_ONE_TRANSFER; // update to one_transfer allowance
                    // leave expiration time alone
                } else if (transferValid && NYCPatternType.SUFFOLK_ONE_TRANSFER.equals(metrocardTransferSource)) {
                    // transfer slip already bought, but consume transfer allowance
                    metrocardTransferSource = null;
                    metrocardTransferExpiry = maxClockTime;
                } else {
                    // Buy an SCT ticket
                    // Note that Suffolk County Transit does not use the MetroCard system, but it simplifies implementation
                    // if we treat it like it does. This does prevent a user from holding a Metrocard transfer
                    // and a Suffolk transfer simultaneously, but I can't imagine why this would be useful.
                    // Suffolk <-> NICE is handled explicitly, and the other MetroCard services are so far
                    // from Suffolk County that you would never to Subway -> SCT -> Subway or
                    // SCT -> Subway -> SCT
                    cumulativeFare += NYCStaticFareData.SUFFOLK_FARE;
                    metrocardTransferSource = NYCPatternType.SUFFOLK;
                    metrocardTransferExpiry = Math.min(boardTime + NYCStaticFareData.SUFFOLK_TRANSFER_VALIDITY_TIME_SECONDS, maxClockTime);
                }

                // TODO assuming that there is no free transfer from NICE_ONE_TRANSFER?
            }

            // III.G. STATEN ISLAND FERRY
            else if (NYCPatternType.STATEN_ISLAND_FERRY.equals(patternType)) {
                // No fare interaction, as the Staten Island Ferry is free. But we do use the SI ferry to "enforce"
                // the two-transfer rule for Local Bus -> SIR -> Bus/Subway in lower Manhattan and vice-versa, so
                // update Metrocard transfer allowance appropriately
                if (NYCPatternType.LOCAL_BUS_TO_SIR.equals(metrocardTransferSource)) metrocardTransferSource = NYCPatternType.LOCAL_BUS_TO_SIR_TO_SI_FERRY;
                else if (NYCPatternType.METROCARD_LOCAL_BUS.equals(metrocardTransferSource)) metrocardTransferSource = NYCPatternType.LOCAL_BUS_TO_SI_FERRY;
                else if (NYCPatternType.METROCARD_SUBWAY.equals(metrocardTransferSource)) metrocardTransferSource = NYCPatternType.SUBWAY_TO_SI_FERRY;
                // prevent the router from riding the SI Ferry twice to get a free trip entirely on Staten Island by doing bus -> SI Ferry -> SI Ferry -> SIR -> Bus
                // Don't take away the transfer allowance when doing this, just remove the special "ferry" privileges
                // To get the special Staten Island transfer, you must ride the ferry an odd number of times, which means
                // you must have started on Staten Island and ended in Manhattan, or vice-versa.
                else if (NYCPatternType.LOCAL_BUS_TO_SI_FERRY.equals(metrocardTransferSource)) metrocardTransferSource = NYCPatternType.METROCARD_LOCAL_BUS;
                else if (NYCPatternType.SUBWAY_TO_SI_FERRY.equals(metrocardTransferSource)) metrocardTransferSource = NYCPatternType.METROCARD_SUBWAY;
                else if (NYCPatternType.LOCAL_BUS_TO_SIR_TO_SI_FERRY.equals(metrocardTransferSource)) metrocardTransferSource = NYCPatternType.LOCAL_BUS_TO_SIR;
            }

            // III.H. METRO-NORTH RAILROAD
            else if (NYCPatternType.METRO_NORTH_PEAK.equals(patternType) || NYCPatternType.METRO_NORTH_OFFPEAK.equals(patternType)) {
                boolean thisRidePeak = NYCPatternType.METRO_NORTH_PEAK.equals(patternType);
                MetroNorthLine thisRideLine = fareData.mnrLineForPattern.get(pattern);
                int thisRideDirection = transitLayer.tripPatterns.get(pattern).directionId;

                if (metroNorthBoardStop != -1) {
                    // this is the second, etc. ride in a Metro-North trip
                    if (thisRideDirection != metroNorthDirection || thisRideLine != metroNorthLine) {
                        // we have changed direction or line. Pay for the previous ride, and reset the Metro-North transfer allowance
                        // No via fares on Metro-North, unlike LIRR
                        // TODO copy-pasted code here!
                        TIntObjectMap<TIntIntMap> overrideFares = metroNorthPeak ? mnrPeakFareOverrides : mnrOffPeakFareOverrides;
                        int mnrFare;
                        if (overrideFares.containsKey(metroNorthBoardStop) && overrideFares.get(metroNorthBoardStop).containsKey(metroNorthAlightStop)) {
                            mnrFare = overrideFares.get(metroNorthBoardStop).get(metroNorthAlightStop);
                        } else {
                            mnrFare = fareData.getMetroNorthFare(metroNorthBoardStop, metroNorthAlightStop,
                                    metroNorthPeak);
                        }

                        int mnrDiscount = getToMnrDiscountForMetrocardTransferSource(metrocardTransferSource);
                        if (mnrDiscount > 0 && metroNorthBoardTime <= metrocardTransferExpiry) {
                            // Clear MetroCard transfer allowance
                            metrocardTransferSource = null;
                            metrocardTransferExpiry = maxClockTime;
                            mnrFare -= mnrDiscount;
                            if (mnrFare < 0) {
                                LOG.warn("Discount on MNR is larger than MNR fare; assuming it's free.");
                                mnrFare = 0;
                            }
                        } else {
                            if (metroNorthMetrocardTransferSource != null) {
                                // NB this overwrites any previous MetroCard transfer information
                                // You can't carry a transfer with you on full-fare MNR
                                metrocardTransferSource = metroNorthMetrocardTransferSource;
                                metrocardTransferExpiry = Math.min(metroNorthBoardTime + NYCStaticFareData.METROCARD_TRANSFER_VALIDITY_TIME_SECONDS, maxClockTime);
                            }
                        }

                        // Reset Metro-North transfer allowance for the new ride
                        cumulativeFare += mnrFare;
                        metroNorthBoardStop = boardStop;
                        metroNorthAlightStop = alightStop;
                        metroNorthPeak = thisRidePeak;
                        metroNorthDirection = thisRideDirection;
                        metroNorthLine = thisRideLine;
                        metroNorthBoardTime = boardTime;
                    } else {
                        // same direction transfer, just extend existing Metro-North ride
                        metroNorthAlightStop = alightStop;
                        metroNorthPeak |= thisRidePeak; // any ride that involves a peak ride anywhere is a peak fare.
                    }
                } else {
                    // new metro-north ride
                    metroNorthBoardStop = boardStop;
                    metroNorthAlightStop = alightStop;
                    metroNorthPeak = thisRidePeak;
                    metroNorthDirection = thisRideDirection;
                    metroNorthLine = thisRideLine;
                    metroNorthBoardTime = boardTime;
                }
            }

            // III.I. NYC FERRY
            else if (NYCPatternType.NYC_FERRY.equals(patternType)) {
                // simple, just increment the fare, no transfers, don't adjust xfer allowances
                cumulativeFare += NYCStaticFareData.NYC_FERRY_FARE;
            }

            // III.J. NYC FERRY FREE SHUTTLE BUS
            // I can't find anywhere where it says that these can only be used in conjunction with
            // a ferry. Pickup_type and dropoff_type is 0 at all stops, but we could change that to
            // only allow pickups/dropoffs at the ferry terminal.
            else if (NYCPatternType.NYC_FERRY_BUS.equals(patternType)) {
                // do nothing, but keep this here so we don't throw an unknown pattern type error later
            }

            // III.K. LONG ISLAND RAIL ROAD
            // TODO refactor to use pattern type
            else if (fareData.allLirrPatterns.contains(pattern)) {
                if (lirrBoardStops == null) {
                    // new ride on the LIRR
                    lirrBoardStops = new ArrayList<>();
                    lirrAlightStops = new ArrayList<>();
                    lirrDirections = new ArrayList<>();
                    lirrPeak.clear();
                    lirrBoardTimes.clear();
                    lirrRideIndex = 0;

                    LIRRStop lirrBoardStop = fareData.lirrStopForTransitLayerStop.get(boardStop);
                    LIRRStop lirrAlightStop = fareData.lirrStopForTransitLayerStop.get(alightStop);

                    if (lirrBoardStop == null) throw new IllegalStateException("No LIRRStop found for transit layer stop " + boardStop);
                    if (lirrAlightStop == null) throw new IllegalStateException("No LIRRStop found for transit layer stop " + alightStop);

                    lirrBoardStops.add(lirrBoardStop);
                    lirrBoardTimes.add(boardTime);
                    lirrAlightStops.add(lirrAlightStop);
                    lirrDirections.add(LIRRTransferAllowance.LIRRDirection.forGtfsDirection(transitLayer.tripPatterns.get(pattern).directionId));
                    lirrPeak.set(lirrRideIndex, fareData.peakLirrPatterns.contains(pattern));
                } else {
                    // continue existing ride on the LIRR
                    if (boardStop != previousAlightStop) {
                        throw new IllegalStateException("LIRR transfer allowance preserved after on-street transfer!");
                    }

                    lirrRideIndex++;
                    lirrBoardStops.add(fareData.lirrStopForTransitLayerStop.get(boardStop));
                    lirrBoardTimes.add(boardTime);
                    lirrAlightStops.add(fareData.lirrStopForTransitLayerStop.get(alightStop));
                    lirrDirections.add(LIRRTransferAllowance.LIRRDirection.forGtfsDirection(transitLayer.tripPatterns.get(pattern).directionId));
                    lirrPeak.set(lirrRideIndex, fareData.peakLirrPatterns.contains(pattern));
                }
            }

            // III.L. WESTCHESTER BxM4C MANHATTAN EXPRESS
            // This has a special no-transfer fare, while other Westchester Bee-Line routes are just MetroCard Local Buses
            // Don't touch transfer allowances here (although I suppose this might actually clear you MetroCard transfer
            // allowance, but that's not documented)
            else if (NYCPatternType.WESTCHESTER_BXM4C.equals(patternType)) {
                cumulativeFare += NYCStaticFareData.BXM4C_FARE;
            }

            // III.M. AIRTRAIN JFK
            // Flat fare, don't touch transfer allowance. I suppose IRL this might clear the transfer allowance since you
            // do pay with your MetroCard, but again, not documented
            else if (NYCPatternType.AIRTRAIN_JFK.equals(patternType)) {
                // pay fare on entry or exit at Howard Beach or Jamaica
                // I assume that if you go from Howard Beach to Jamaica you pay twice... which is why
                // these are separate ifs
                // (I'm also not sure you can get from Howard Beach to Jamaica without a transfer, but if statements are cheap)
                if (fareData.airtrainJfkFareStops.contains(boardStop)) cumulativeFare += NYCStaticFareData.AIRTRAIN_JFK_FARE;
                if (fareData.airtrainJfkFareStops.contains(alightStop)) cumulativeFare += NYCStaticFareData.AIRTRAIN_JFK_FARE;
            }

            else {
                throw new IllegalStateException("Unrecognized pattern type!");
            } // END CHAINED IF OVER PATTERN TYPES
        } // END LOOP OVER RIDES

        // IF WE HAVE AN ON-STREET TRANSFER, RECORD WHETHER WE'VE LEFT THE LIRR/METRO-NORTH/SUBWAY FOR DOMINATION PURPOSES
        // don't consider a loop transfer to be an on-street transfer - the router will cunningly use this to get rid of
        // negative transfer allowances.
        if (state.pattern == -1 && state.back != null && state.stop != state.back.stop) {
            // clear LIRR transfer allowance - no on street transfers with LIRR
            lirr = null;
            // clear MNR transfer allowance - no on street transfers with MNR
            metroNorthBoardStop = metroNorthAlightStop = metroNorthDirection = -1;
            metroNorthPeak = false;
            metroNorthLine = null;
            // record if we've left the subway paid area
            if (inSubwayPaidArea) {
                inSubwayPaidArea = hasBehindGatesTransfer(state.back.stop, state.stop, fareData);
            }
        }

        return new FareBounds(cumulativeFare,
                new NYCTransferAllowance(lirr, metrocardTransferSource, metrocardTransferExpiry,
                        inSubwayPaidArea,
                        metroNorthBoardStop, metroNorthDirection, metroNorthPeak, metroNorthLine
                ));
    }

    /** return true if you can transfer from subway stop from to to without leaving the system */
    public static boolean hasBehindGatesTransfer (int from, int to, NYCFareDataCache fareData) {
        if (from == to) return true; // same platform
        else {
            String previousFareArea = fareData.fareAreaForStop.get(from);
            String thisFareArea = fareData.fareAreaForStop.get(to);
            // both in the same fare area (behind gates)
            // okay to use == here since fare areas are interned - warning can be ignored
            //noinspection StringEquality,ConditionCoveredByFurtherCondition
            return previousFareArea != null && thisFareArea != null && previousFareArea == thisFareArea;
        }
    }

    private int getToMnrDiscountForMetrocardTransferSource (NYCPatternType metrocardTransferSource) {
        if (metrocardTransferSource == null || toMetroNorthDiscounts == null) return 0;
        else return toMetroNorthDiscounts.getOrDefault(metrocardTransferSource, 0);
    }

    private int getToLirrDiscountForMetrocardTransferSource (NYCPatternType metrocardTransferSource) {
        if (metrocardTransferSource == null || toLirrDiscounts == null) return 0;
        else return toLirrDiscounts.getOrDefault(metrocardTransferSource, 0);
    }

    @Override
    public String getType() {
        return "nyc";
    }

    public enum MetroNorthLine {
        HARLEM, HUDSON, NEW_HAVEN // and branches
    }

    /** Used from API to override particular Metro-North and LIRR fares */
    public static class FareOverride {
        public String fromStopId;
        public String toStopId;
        public String viaStopId; // only for LIRR
        public int peakFare;
        public int offPeakFare;
    }
}
