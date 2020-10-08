package com.conveyal.r5.analyst.fare.nyc;

import com.google.common.collect.Sets;

import java.util.Set;

public enum NYCPatternType {
    // TODO move peakLirrPatterns and allLirrPatterns in here
    /** A local bus ($2.75), with free transfers to subway or other local bus */
    METROCARD_LOCAL_BUS,
    /** NYC subway, free within-system transfers, and free transfers to local bus. 3.75 upgrade to Express Bus */
    METROCARD_SUBWAY,
    /**
     * Metrocard NICE routes are _almost_ the same as Metrocard local bus, but offer _two_ transfers to add'l NICE buses
     * At least, that seems to be what the text here implies, but it's not completely clear:
     * https://www.vsvny.org/vertical/sites/%7BBC0696FB-5DB8-4F85-B451-5A8D9DC581E3%7D/uploads/NICE-n1_Maps_and_Schedules.pdf
     * "two connecting NICE bus routes" I assume they mean two more after the first one.
     * We're not handling all rules about where you can transfer.
     */
    METROCARD_NICE,

    /**
     * This is not assigned to any patterns, but is used as a metrocard transfer source after two NICE rides,
     * to indicate that one more NICE ride is free, but no rides on other services are transfer-eligible.
     */
    METROCARD_NICE_ONE_TRANSFER,

    /** Staten Island Railway. This is different from the subway because it provides a free transfer to/from all subway lines */
    STATEN_ISLAND_RWY,
    /** MTA Express buses, $6.75 or $3.75 upgrade from local bus (yes, it's cheaper to transfer from local bus) */
    METROCARD_EXPRESS_BUS,
    STATEN_ISLAND_FERRY,
    METRO_NORTH_PEAK,
    METRO_NORTH_OFFPEAK,
    // TODO currently unused
    LIRR_OFFPEAK, LIRR_PEAK,
    NYC_FERRY,
    NYC_FERRY_BUS, // Free shuttle bus to NYC Ferry terminals
    /**
     * The BxM4C is a special express bus to Manhattan with no transfers.
     * Other Westchester County routes are identical to MTA local buses.
     */
    WESTCHESTER_BXM4C,

    SUFFOLK,

    /** Used after a single transfer on Suffolk County Transit */
    SUFFOLK_ONE_TRANSFER,

    AIRTRAIN_JFK,

    /** Special cases for Staten Island Railway
     * You get a free transfer from Local Bus -> SIR -> Subway/Local Bus in lower Manhattan.
     * We don't reproduce this exactly because of the complexity of handling all the possible board points,
     * but we do allow you to do local bus -> SIR -> SI ferry -> bus/subway and bus/subway -> SI ferry
     * -> SIR -> local bus. By enforcing the use of the ferry, and not allowing non-MetroCard services between
     * the ferry and the SIR/local bus/subway, we sort of capture the correct behavior.
     * These states represent after riding Local Bus -> SIR, after riding Local Bus -> SIR -> Ferry,
     * and after riding Subway/Bus -> Ferry, and Subway/Bus -> Ferry -> SIR.
     *
     * We don't enforce the "no other services between Subway/Bus and SIR" rule on the reverse trip, because
     * it would require keeping local bus/subway allowances in Lower Manhattan separate based on whether there was
     * another service used. Since the commuter rail is quite far from the SI ferry landing, the only other
     * services that could be used to circumvent the "get off near the ferry" rule are the NYC Ferry and the
     * NYC Ferry buses. In any case, since you don't tag out of the subway/bus, there's no way for the MetroCard
     * system to enforce this rule.
     */
    LOCAL_BUS_TO_SIR, LOCAL_BUS_TO_SIR_TO_SI_FERRY,
    // keep these separate as they have different transfer allowances for reboarding the subway -
    // important if you took a local bus on Staten Island to St George, you can still get on the subway
    // in Manhattan for free
    LOCAL_BUS_TO_SI_FERRY, SUBWAY_TO_SI_FERRY,
    LOCAL_BUS_OR_SUBWAY_TO_SI_FERRY_TO_SIR // same transfer allowance after riding ferry

    ;

    /**
     * The services that you can get a discounted transfer to from NICE. We do this because NICE has
     * very specific lists of connecting routes, that we're not implementing. But we do assume that if
     * you have to take another transit service (e.g. LIRR) to connect between NICE and some other transit
     * service, you lose the NICE transfer discount on the next ride (so you can't do NICE -> LIRR -> NICE
     * and get the second NICE ride for free). This set is used to determine if a discounted transfer is available,
     * otherwise the transfer allowance is cleared.
     */
    public static final Set<NYCPatternType> niceTransfers =
            Sets.immutableEnumSet(METROCARD_LOCAL_BUS, METROCARD_EXPRESS_BUS, METROCARD_SUBWAY, METROCARD_NICE,
                    METROCARD_NICE_ONE_TRANSFER, STATEN_ISLAND_RWY, SUFFOLK);
}
