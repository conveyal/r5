package com.conveyal.r5.analyst.fare.nyc;

import com.conveyal.r5.analyst.fare.TransferAllowance;
import com.conveyal.r5.profile.McRaptorSuboptimalPathProfileRouter;
import com.csvreader.CsvReader;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimap;
import gnu.trove.iterator.TIntIterator;
import gnu.trove.iterator.TObjectIntIterator;
import gnu.trove.list.TIntList;
import gnu.trove.map.TIntIntMap;
import gnu.trove.map.TIntObjectMap;
import gnu.trove.map.TObjectDoubleMap;
import gnu.trove.map.TObjectIntMap;
import gnu.trove.map.hash.TIntIntHashMap;
import gnu.trove.map.hash.TIntObjectHashMap;
import gnu.trove.map.hash.TObjectDoubleHashMap;
import gnu.trove.map.hash.TObjectIntHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.*;

/**
 * Calculates fares for and represents transfer allowances on the MTA Long Island Rail Road. One nice thing is that the LIRR is a small
 * network, so we can be kinda lazy with how many journey prefixes get eliminated - we only consider journey prefixes
 * comparable under the more strict domination rules (Theorem 3.2 in the paper at https://files.indicatrix.org/Conway-Stewart-2019-Charlie-Fare-Constraints.pdf)
 * if they boarded at the same stop, alighted at the same stop, used the same stop to transfer from inbound to outbound
 * (if there was an opposite-direction transfer), and used the same combination of peak/offpeak services.
 *
 * LIRR fares are really complicated, because of complex and undocumented transfer rules. The fare chart is available at
 * http://web.mta.info/lirr/about/TicketInfo/Fares.htm, but it does not include anything about fares where you have to ride
 * inbound and then outbound (for instance, based on the fare chart, Montauk to Greenport on the eastern end of Long
 * Island are both in Zone 14, and thus should be a $3.25 fare, but to get between them, you have to ride at least to
 * Bethpage (Zone 7). So there must be a more expensive fare, because otherwise anyone who was going from Montauk to Babylon,
 * say, would just say they were planning to transfer and go back outbound.
 *
 * LIRR deals with this situation by specifying via fares. They have no documentation on these that I can find, so I
 * scraped them from their fare calculator at lirr42.mta.info. For the Montauk to Greenport trip described above, for
 * example, the fare calculator returns that a trip via Jamaica is 31.75 (offpeak), and a trip via Hicksville is 28.00
 * (offpeak). This is still ambiguous - what if you make a trip from Montauk to Greenport but change at Bethpage instead
 * of Hicksville? We are assuming that in this case, you would be able to use the via fare for Hicksville.
 *
 * If there is a
 * via fare for a station specified, we assume that it can also be used for any station closer to the origin/destination
 * than the via station. We define closer as being that that station is reachable from the via station via only outbound
 * trains in the common Inbound-Outbound transfer case, and reachable via only inbound trains in the rare Outbound-Inbound
 * case. If there is no via fare specified, we use two one-way tickets. (The second ticket may be a via fare if there is
 * another opposite direction transfer, for instance Hicksville to Oceanside via Babylon and Lynbrook, but otherwise we
 * use via fares greedily. For an A-B-C-D trip, if there is a via fare from A to C via B, we will buy an ABC and a CD fare
 * even if AB and BCD would be cheaper.)
 *
 * This is further complicated by the fact that there are other transfers that are not via transfers. For example, to
 * travel from Atlantic Terminal to Ronkonkoma on LIRR train 2000, you must change at Jamaica. We thus assume that you
 * can change between any two trains anywhere and as long as you continue to travel in the same direction, it is treated
 * as a single ride for fare calculation purposes.
 *
 * @author mattwigway
 */
public class LIRRTransferAllowance extends TransferAllowance {
    private static final Logger LOG = LoggerFactory.getLogger(LIRRTransferAllowance.class);
    private static final TIntObjectMap<TIntIntMap> peakDirectFares = new TIntObjectHashMap<>();
    private static final TIntObjectMap<TIntIntMap> offpeakDirectFares = new TIntObjectHashMap<>();

    /** Map from fromStop, toStop, viaStop to fare. Via is last because we allow trips with unmatched via stop to match to other via stop */
    private static final Map<LIRRStop, Map<LIRRStop, TObjectIntMap<LIRRStop>>> peakViaFares = new HashMap<>();
    private static final Map<LIRRStop, Map<LIRRStop, TObjectIntMap<LIRRStop>>> offpeakViaFares = new HashMap<>();

    /** map from stop to fare zone */
    private static final TObjectIntMap<LIRRStop> fareZoneForStop = new TObjectIntHashMap<>();

    private static int maxLirrFareTemp = 0;

    /** the maximum fare to travel anywhere in the LIRR system */
    public static final int MAX_LIRR_FARE;

    /** if a stop pair is present in this set, the second stop can be reached by only inbound trains from the first stop */
    @VisibleForTesting
    static final Multimap<LIRRStop, LIRRStop> inboundDownstreamStops = HashMultimap.create();

    /** if a stop pair is present in this set, the second stop can be reached by only outbound trains from the first stop */
    @VisibleForTesting
    static final Multimap<LIRRStop, LIRRStop> outboundDownstreamStops = HashMultimap.create();

    static {
        loadDirectFares();
        loadViaFares();
        loadZones();
        loadDownstream();
        MAX_LIRR_FARE = maxLirrFareTemp;
    }

    /** Fare for the LIRR so far on this journey */
    public final int cumulativeFare;

    /** The boarding stop of the most recently purchased LIRR ticket */
    public final LIRRStop boardStop;

    /** The transfer stop where the user changed direction */
    public final LIRRStop viaStop;

    /** Where the user alighted */
    public final LIRRStop alightStop;

    /** What direction the user started out traveling in */
    public final LIRRDirection initialDirection;

    /** Was a peak train ridden before any opposite-direction transfer */
    public final boolean peakBeforeDirectionChange;

    /** Was a peak train ridden after any opposite-direction transfer, always false if there was no direction change */
    public final boolean peakAfterDirectionChange;

    /** What time was the last LIRR ticket purchased? */
    public final int mostRecentTicketPurchaseTime;

    /** backreference so we can refer to scenario fare modifications */
    private final NYCInRoutingFareCalculator fareCalculator;

    /**
     * Compute an LIRR fare. Currently assumes that if any journey in the sequence is a peak journey, the peak fare will
     * be charged for the entire journey. It might be possible to get a cheaper fare by combining an off-peak and peak fare
     * purchased separately, but I am assuming that people don't do this. Due to the separate consideration of peak and off-peak
     * transfer allowances described above, this should not cause algorithmic difficulties.
     *
     * @param boardStops
     * @param alightStops
     * @param directions
     * @param peak
     */
    public LIRRTransferAllowance (List<LIRRStop> boardStops, List<LIRRStop> alightStops, List<LIRRDirection> directions,
            TIntList boardTimes, BitSet peak, NYCInRoutingFareCalculator fareCalculator) {
        if (boardStops.size() == 0) {
            throw new IllegalArgumentException("Empty LIRR stop list!");
        }

        this.fareCalculator = fareCalculator;

        // main fare calculation loop
        int fareFromPreviousTickets = 0; // some complex LIRR journeys require multiple tickets
        int cumulativeFareThisTicket = 0;
        LIRRStop initialStop = boardStops.get(0); // source stop of current LIRR *ticket*
        LIRRDirection initialDirection = directions.get(0); // source direction of current LIRR *ticket*
        boolean thisTicketPeak = false; // has a peak train been used on this LIRR *ticket*
        boolean thisDirectionPeak = false; // has a peak train been used in this direction on this ticket (used when there is no via fare, to get cumulative fares)
        boolean lastDirectionPeak = false;
        int nDirectionChanges = 0;
        int timeAtInitialStop = boardTimes.get(0);
        int timeAtViaStop = 0;
        LIRRStop viaStop = null; // via stop of current LIRR *ticket*

        for (int i = 0; i < boardStops.size(); i++) {
            LIRRStop boardStop = boardStops.get(i);
            LIRRStop alightStop = alightStops.get(i);
            LIRRDirection direction = directions.get(i);

            if (direction.equals(initialDirection) && nDirectionChanges == 0) {
                // assuming you can change to another train in the same direction as if you never got off
                thisDirectionPeak |= peak.get(i);
                thisTicketPeak |= peak.get(i);
                cumulativeFareThisTicket = getDirectFare(initialStop, alightStop, thisTicketPeak);
            } else {
                // this can only happen on the second or more ride of a ticket
                if (!directions.get(i).equals(directions.get(i - 1))) {
                    // we changed direction since the last ride, not continuing a ride in opposite direction
                    nDirectionChanges++;

                    if ((nDirectionChanges == 1) && (boardStop.equals(alightStops.get(i - 1)))) {
                        // we are on the second direction. continue with current ticket, unless we have left the system.
                        viaStop = boardStop;
                        timeAtViaStop = boardTimes.get(i);
                        lastDirectionPeak = thisDirectionPeak;
                        thisDirectionPeak = peak.get(i);
                    } else {
                        // time to buy a new ticket
                        fareFromPreviousTickets += cumulativeFareThisTicket; // left over from last iteration
                        thisTicketPeak = peak.get(i);
                        thisDirectionPeak = peak.get(i);
                        lastDirectionPeak = false;
                        initialStop = boardStop;
                        viaStop = null;
                        nDirectionChanges = 0;
                        initialDirection = direction;
                        timeAtInitialStop = boardTimes.get(i);

                        // not += as this is just the fare for _this_ ticket, which is a new ticket
                        cumulativeFareThisTicket = getDirectFare(initialStop, alightStop, thisTicketPeak);
                        continue; // move to next ride
                    }
                }

                // continue the second ride after changing direction
                // couldn't set these until all changing direction was done
                thisDirectionPeak |= peak.get(i);
                thisTicketPeak |= peak.get(i);

                // continue ride in this direction
                // try getting the via fare
                if (viaStop == null) {
                    throw new NullPointerException("Via stop is null");
                }

                try {
                    cumulativeFareThisTicket = getViaFare(initialStop, alightStop, viaStop, lastDirectionPeak,
                            thisDirectionPeak, initialDirection);
                } catch (NoMatchingViaFareException e) {
                    // buy a new ticket for the second part of this ride
                    // it is important to do this, rather than just calculate a quasi-via fare as the sum of the two
                    // non-via fares, so that you can get a discounted transfer on the second ticket.
                    // For instance, Hicksville to Oceanside by way of Babylon and Lynbrook might be cheapest using a
                    // one-way ticket from Hicksville to Babylon, then a via fare from Babylon to Oceanside via Lynbrook.
                    // While we generally assume greedy purchasing because otherwise the algorithm becomes really difficult,
                    // if the first ride requires a separate ticket, there's no reason to force no transfer after the second.
                    fareFromPreviousTickets += getDirectFare(initialStop, viaStop, lastDirectionPeak);
                    thisTicketPeak = thisDirectionPeak;
                    // thisDirectionPeak is unchanged
                    lastDirectionPeak = false; // reset
                    initialStop = viaStop;
                    viaStop = null;
                    nDirectionChanges = 0;
                    initialDirection = direction;
                    cumulativeFareThisTicket = getDirectFare(viaStop, alightStop, thisDirectionPeak);
                    timeAtInitialStop = timeAtViaStop;
                }
            }
        }

        this.boardStop = initialStop; // for this ticket
        this.viaStop = viaStop;
        this.initialDirection = initialDirection;
        this.alightStop = alightStops.get(alightStops.size() - 1);
        this.peakBeforeDirectionChange = (viaStop == null ? thisDirectionPeak : lastDirectionPeak);
        this.peakAfterDirectionChange = (viaStop == null ? false : thisDirectionPeak); // always set to false when there has been no direction change
        this.cumulativeFare = fareFromPreviousTickets + cumulativeFareThisTicket;
        this.mostRecentTicketPurchaseTime = timeAtInitialStop;

        if (this.cumulativeFare == 0) {
            LOG.warn("Cumulative fare for LIRR is zero!");
        }
    }

    /** Get a direct fare, with error handling */
    public int getDirectFare (LIRRStop fromStop, LIRRStop toStop, boolean peak) {
        Map<LIRRStop, TObjectIntMap<LIRRStop>> overrides =
                peak ? fareCalculator.lirrPeakDirectFareOverrides : fareCalculator.lirrOffPeakDirectFareOverrides;

        // first check for a manual override to the fare
        if (overrides.containsKey(fromStop) && overrides.get(fromStop).containsKey(toStop)) {
            return overrides.get(fromStop).get(toStop);
        }

        // otherwise, compute a zonal fare
        int fromZone = fareZoneForStop.get(fromStop);
        int toZone = fareZoneForStop.get(toStop);
        int fare = (peak ? peakDirectFares : offpeakDirectFares).get(fromZone).get(toZone);

        if (fare == 0) throw new IllegalArgumentException("fare zones not found!");

        return fare;
    }

    /** Get fare for a trip that changed from inbound to outbound or vice versa */
    public int getViaFare (LIRRStop fromStop, LIRRStop toStop, LIRRStop viaStop, boolean lastDirectionPeak,
                           boolean thisDirectionPeak, LIRRDirection initialDirection) throws NoMatchingViaFareException {
        Map<LIRRStop, Map<LIRRStop, TObjectIntMap<LIRRStop>>> stockViaFares =
                (lastDirectionPeak || thisDirectionPeak) ? peakViaFares : offpeakViaFares;

        Map<LIRRStop, Map<LIRRStop, TObjectIntMap<LIRRStop>>> overrideFares =
                (lastDirectionPeak || thisDirectionPeak) ? fareCalculator.lirrPeakViaFareOverrides : fareCalculator.lirrOffPeakViaFareOverrides;

        int twoTicketFare = getDirectFare(fromStop, viaStop, lastDirectionPeak) +
                getDirectFare(viaStop, toStop, thisDirectionPeak);

        boolean found = false;
        int viaFare = 0;

        // The full loop is run twice, once with override fares, and once with stock fares.
        // The reason to do this is that we want to find _any_ matching fare from fare overrides, even if via stop
        // does not match exactly, before we resort to stock fares. So if you override fares from Jamaica to Port Washington
        // via Penn Station, that fare should also apply to Jamaica to Port Washington via Woodside, unless a cheaper
        // fare can be found that still applies.
        for (Map<LIRRStop, Map<LIRRStop, TObjectIntMap<LIRRStop>>> viaFares : Lists
                .newArrayList(overrideFares, stockViaFares)) {
            if (viaFares.containsKey(fromStop) && viaFares.get(fromStop).containsKey(toStop)) {
                TObjectIntMap<LIRRStop> viaFaresForOriginDestination = viaFares.get(fromStop).get(toStop);

                if (viaFaresForOriginDestination.containsKey(viaStop)) {
                    // ah, good, there is a fare for exactly this ride
                    viaFare = viaFaresForOriginDestination.get(viaStop);
                    found = true;
                } else {
                    // allow via fares to be used even when not transferring at specific locations. Use the cheapest via
                    // fare that transfers at a stop "upstream" of the stop.
                    viaFare = Integer.MAX_VALUE;

                    for (TObjectIntIterator<LIRRStop> fareIterator = viaFaresForOriginDestination
                            .iterator(); fareIterator.hasNext(); ) {
                        fareIterator.advance();

                        LIRRStop fareViaStop = fareIterator.key();

                        Multimap<LIRRStop, LIRRStop> downstreamStops;
                        if (initialDirection == LIRRDirection.INBOUND) {
                            // not backwards - we can apply the via fare for transfers at any stop that is "outbound of"
                            // the specified via stop, when we started inbound.
                            downstreamStops = outboundDownstreamStops;
                        } else if (initialDirection == LIRRDirection.OUTBOUND) {
                            downstreamStops = inboundDownstreamStops;
                        } else {
                            throw new IllegalArgumentException("Impossible direction for LIRR");
                        }

                        if (downstreamStops.containsEntry(fareViaStop, viaStop)) {
                            // this via fare can be applies to this viaStop, get cheapest
                            viaFare = Math.min(fareIterator.value(), viaFare);
                            found = true;
                        }
                    }
                }
            }

            if (found) break; // don't fall through to stock fares if we found the stop in the override fares
        }

        if (!found) throw new NoMatchingViaFareException();

        if (twoTicketFare < viaFare) {
            LOG.warn("Travel from {} to {} via {} is cheaper by buying two tickets than using via fare",
                    fromStop, toStop, viaStop);
            throw new NoMatchingViaFareException(); // force new ticket purchase
        }

        if (viaFare == 0) {
            throw new InternalError("LIRR via fare is zero!"); // sanity check, should not be possible
        }

        return viaFare;
    }

    /**
     * Does this provide as good as or better than transfers to all future services?
     * Rather than actually figure this out, just treat only LIRR tickets that boarded at the same place, transferred at the same place,
     * alighted at the same place started in the same direction, used the same combo of peak and off-peak services as comparable.
     * Since the LIRR is a small network, and we clear LIRR transfers as soon as you leave the LIRR system, this should be tractable.
     * @param otherAllowance
     * @return
     */
    @Override
    public boolean atLeastAsGoodForAllFutureRedemptions (TransferAllowance otherAllowance) {
        LIRRTransferAllowance other = (LIRRTransferAllowance) otherAllowance;
        // it could be preferable to not have an LIRR transfer allowance, see note about Metro-North in NYCTransferAllowance.
        if (other == null) return false;
        else return (boardStop.equals(other.boardStop)) &&
                (viaStop == other.viaStop) && // okay to use == on enum constants, and neatly handles nulls
                (initialDirection.equals(other.initialDirection)) &&
                (peakBeforeDirectionChange == other.peakBeforeDirectionChange) &&
                (peakAfterDirectionChange == other.peakAfterDirectionChange);
                // no need to compare cumulative fare here, that's done separately -- and in fact we wouldn't want to
                // b/c it might throw out an LIRR journey in favor of a more expensive overall journey that doesn't use the LIRR as much.
    }

    /**
     * Again, producing a weak upper bound for simplicity, and given the small size of the LIRR network it should be
     * tractable. We know the max transfer allowance can't be any more than if you were to just buy the most expensive new ticket.
     * Since we clear LIRR transfer allowances as soon as you egress from an LIRR station, this should not cause tractability issues.
     * NB we are no longer using maximum transfer allowances in the algorithm.
     */

    public int getMaxTransferAllowance () {
        return MAX_LIRR_FARE;
    }


    /**
     * Load LIRR fare information from classpath.
     */
    private static void loadDirectFares () {
        InputStream is = null;
        try {
            is = LIRRTransferAllowance.class.getClassLoader().getResourceAsStream("fares/nyc/lirr/lirr_zonal_fares.csv");
            CsvReader rdr = new CsvReader(is, ',', Charset.forName("UTF-8"));
            rdr.readHeaders();
            while (rdr.readRecord()) {
                int fromZone = Integer.parseInt(rdr.get("from_zone"));
                int toZone = Integer.parseInt(rdr.get("to_zone"));
                int fare = Integer.parseInt(rdr.get("amount"));
                maxLirrFareTemp = Math.max(maxLirrFareTemp, fare);
                if (rdr.get("peak").equals("True")) {
                    if (!peakDirectFares.containsKey(fromZone)) peakDirectFares.put(fromZone, new TIntIntHashMap());
                    peakDirectFares.get(fromZone).put(toZone, fare);
                } else {
                    if (!offpeakDirectFares.containsKey(fromZone)) offpeakDirectFares.put(fromZone, new TIntIntHashMap());
                    offpeakDirectFares.get(fromZone).put(toZone, fare);
                }
            }
        } catch (IOException e) {
            LOG.error("IO Exception reading LIRR Direct Fares CSV", e);
        } finally {
            try {
                is.close();
            } catch (IOException e) {
                LOG.error("IO Exception closing LIRR Direct Fares CSV", e);
            }
        }
    }

    private static void loadViaFares () {
        InputStream is = null;
        try {
            is = LIRRTransferAllowance.class.getClassLoader().getResourceAsStream("fares/nyc/lirr/via_fares.csv");
            CsvReader rdr = new CsvReader(is, ',', Charset.forName("UTF-8"));
            rdr.readHeaders();
            while (rdr.readRecord()) {
                LIRRStop fromStop = LIRRStop.valueOf(rdr.get("from_stop_id").toUpperCase(Locale.US));
                LIRRStop toStop = LIRRStop.valueOf(rdr.get("to_stop_id").toUpperCase(Locale.US));
                LIRRStop viaStop = LIRRStop.valueOf(rdr.get("via_stop_id").toUpperCase(Locale.US));

                int fare = Integer.parseInt(rdr.get("amount"));
                maxLirrFareTemp = Math.max(maxLirrFareTemp, fare);

                if (rdr.get("peak").equals("True")) {
                    peakViaFares.computeIfAbsent(fromStop, k -> new HashMap<>()).computeIfAbsent(toStop, k -> new TObjectIntHashMap<>()).put(viaStop, fare);
                } else {
                    offpeakViaFares.computeIfAbsent(fromStop, k -> new HashMap<>()).computeIfAbsent(toStop, k -> new TObjectIntHashMap<>()).put(viaStop, fare);
                }
            }
        } catch (IOException e) {
            LOG.error("IO Exception reading LIRR Via Fares CSV", e);
        } finally {
            try {
                is.close();
            } catch (IOException e) {
                LOG.error("IO Exception closing LIRR Via Fares CSV", e);
            }
        }
    }

    /** Create the stop to fare zone mapping */
    private static void loadZones () {
        InputStream is = null;
        try {
            is = LIRRTransferAllowance.class.getClassLoader().getResourceAsStream("fares/nyc/lirr/lirr_stops_fare_zones.csv");
            CsvReader rdr = new CsvReader(is, ',', Charset.forName("UTF-8"));
            rdr.readHeaders();
            while (rdr.readRecord()) {
                String stopId = rdr.get("stop_id").toUpperCase(Locale.US);
                LIRRStop stop = null;
                try {
                    stop = LIRRStop.valueOf(stopId);
                } catch (IllegalArgumentException e) {
                    LOG.warn("LIRR stop {} from fare zones CSV not found (possibly a holiday-only stop)", stopId);
                }
                int fareZone = Integer.parseInt(rdr.get("fare_area"));
                fareZoneForStop.put(stop, fareZone);
            }
        } catch (IOException e) {
            LOG.error("IO Exception reading LIRR Via Fares CSV", e);
        } finally {
            try {
                is.close();
            } catch (IOException e) {
                LOG.error("IO Exception closing LIRR Via Fares CSV", e);
            }
        }
    }

    private static void loadDownstream () {
        InputStream is = null;
        try {
            is = LIRRTransferAllowance.class.getClassLoader().getResourceAsStream("fares/nyc/lirr/descendants.csv");
            CsvReader rdr = new CsvReader(is, ',', Charset.forName("UTF-8"));
            rdr.readHeaders();
            while (rdr.readRecord()) {
                LIRRStop fromStop = LIRRStop.valueOf(rdr.get("stop_id").toUpperCase(Locale.US));
                for (int i = 1; i < rdr.getHeaderCount(); i++) {
                    LIRRStop toStop = LIRRStop.valueOf(rdr.getHeader(i).toUpperCase(Locale.US));
                    String val = rdr.get(i);
                    if (val.equals("I")) {
                        inboundDownstreamStops.put(fromStop, toStop);
                    } else if (val.equals("O")) {
                        outboundDownstreamStops.put(fromStop, toStop);
                    }
                }
            }
        } catch (IOException e) {
            LOG.error("IO Exception reading LIRR Direct Fares CSV", e);
        } finally {
            try {
                is.close();
            } catch (IOException e) {
                LOG.error("IO Exception closing LIRR Direct Fares CSV", e);
            }
        }
    }

    private static final class NoMatchingViaFareException extends Exception {

    }

    public static enum LIRRDirection {
        OUTBOUND, INBOUND;

        public static LIRRDirection forGtfsDirection (int dir) {
            switch (dir) {
                case 0:
                    return OUTBOUND;
                case 1:
                    return INBOUND;
                default:
                    throw new IllegalArgumentException("Direction must be 0/1");
            }
        }
    }
}
