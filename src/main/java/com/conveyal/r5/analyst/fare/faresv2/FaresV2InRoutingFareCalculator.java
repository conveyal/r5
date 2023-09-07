package com.conveyal.r5.analyst.fare.faresv2;

import com.conveyal.r5.analyst.fare.FareBounds;
import com.conveyal.r5.analyst.fare.InRoutingFareCalculator;
import com.conveyal.r5.profile.McRaptorSuboptimalPathProfileRouter;
import com.conveyal.r5.transit.TransitLayer;
import com.conveyal.r5.transit.faresv2.FareLegRuleInfo;
import com.conveyal.r5.transit.faresv2.FareTransferRuleInfo;
import com.conveyal.r5.transit.faresv2.FareTransferRuleInfo.FareTransferType;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import gnu.trove.iterator.TIntIterator;
import gnu.trove.list.TIntList;
import gnu.trove.list.array.TIntArrayList;
import gnu.trove.set.TIntSet;
import gnu.trove.set.hash.TIntHashSet;
import org.roaringbitmap.PeekableIntIterator;
import org.roaringbitmap.RoaringBitmap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.ExecutionException;

import static com.conveyal.r5.analyst.fare.faresv2.IndexUtils.getMatching;

/**
 * A fare calculator for feeds compliant with the GTFS Fares V2 standard (https://bit.ly/gtfs-fares)
 *
 * @author mattwigway
 */
public class FaresV2InRoutingFareCalculator extends InRoutingFareCalculator {
    private static final Logger LOG = LoggerFactory.getLogger(FaresV2InRoutingFareCalculator.class);

    private transient LoadingCache<FareTransferRuleKey, Integer> fareTransferRuleCache = CacheBuilder.newBuilder()
            .maximumSize(1000)
            .build(new CacheLoader<>() {
                @Override
                public Integer load(FareTransferRuleKey fareTransferRuleKey) {
                    return searchFareTransferRule(fareTransferRuleKey);
                }
            });

    /**
     * If true, consider all stops at which an as_route journey boards or alights as potentially triggering a higher
     * fare (e.g. "via" fares used in London, Toronto), and calculate fares based on the most extensive fare leg rule
     * for the trip.
     *
     * If false, simply use the first boarding and last alighting stop of a journey to calculate the fare.
     *
     * This requires setting the order field in fare_leg_rules so that more extensive
     * fare leg rules have lower order. If the system does not have linear systems of zones, the order can be set based on the
     * cost of the fare_leg_rule, as long as adding an additional zone to a trip cannot make the fare go down. Fare leg rules
     * with the same fare must be given the same order for transfer allowances to work correctly; see comments on
     * FaresV2TransferAllowance.potentialAsRouteFareRules.
     *
     * This is hack to address a situation where GTFS-Fares V2 is not (as of this writing) able to correctly represent
     * the GO fare system. The GO fare chart _appears_ to be a simple from-station-A-to-station-B chart, a la WMATA etc.,
     * but it's more nuanced - because of one little word in the fare bylaws
     * (https://www.gotransit.com/static_files/gotransit/assets/pdf/Policies/By-Law_No2A.pdf): "Tariff of Fares attached
     * hereto, setting out the amount to be paid for single one-way travel on the transit system _within the
     * enumerated zones_" - within, not from or to. So if you start in Zone B, backtrack to Zone A, and then ride on to
     * Zone C, you actually owe the A - C fare, not the B - C fare, because you traveled to Zone A. There is currently
     * active discussion in the GTFS-Fares V2 document for how to address this conundrum, but with deadlines looming I
     * have implemented this hack. When useAllStopsWhenCalculatingAsRouteFareNetwork is set to true, when evaluating an
     * as_route fare network, the router will consider rules matching from_area_ids of _any_ stop within the joined
     * as_route trips except the final alight stop, and to_area_ids of _any_ stop except the first board stop. It is not
     * only board stops considered for from and alight stops considered for to, because you might do a trip
     * C - A walk to B - D, and this should cost the A-D fare even though you didn't ever board at A.
     * By setting the order of rules in the feed to have the most
     * expensive first, the proper fare will be found (assuming that extending the trip into a new zone always causes a
     * nonnegative change in the fare).
     *
     * The need to calculate as_route fares based on the full journey extent is not a hypothetical concern in Toronto.
     * Consider this trip: https://projects.indicatrix.org/fareto-examples/?load=broken-yyz-downtown-to-york
     * The second option here is $6.80 but should be $7.80, because it requires a change at Unionville, and Toronto to
     * Unionville is 7.80 even though Toronto to Yonge/407 is only $6.80.
     */
    public boolean useAllStopsWhenCalculatingAsRouteFareNetwork = false;

    @Override
    public FareBounds calculateFare(McRaptorSuboptimalPathProfileRouter.McRaptorState state, int maxClockTime) {
        TIntList patterns = new TIntArrayList();
        TIntList boardStops = new TIntArrayList();
        TIntList alightStops = new TIntArrayList();
        TIntList boardTimes = new TIntArrayList();
        TIntList alightTimes = new TIntArrayList();

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
            alightTimes.add(stateForTraversal.time);

            stateForTraversal = stateForTraversal.back;
        }

        patterns.reverse();
        boardStops.reverse();
        alightStops.reverse();
        boardTimes.reverse();
        alightTimes.reverse();

        int prevFareLegRuleIdx = -1;
        int cumulativeFare = 0;

        RoaringBitmap asRouteFareNetworks = null;
        int asRouteBoardStop = -1;

        // What fare leg rules are potentially applicable to a trip in an as_route fare network
        // used in transfer allowance when useAllStopsWhenCalculatingAsRouteFareNetwork = true.
        // see comment on same-named variable in transfer allowance for detailed explanation.
        int[] potentialAsRouteFareLegRules = null;
        for (int i = 0; i < patterns.size(); i++) {
            int pattern = patterns.get(i);
            int boardStop = boardStops.get(i);
            int alightStop = alightStops.get(i);
            int boardTime = boardTimes.get(i);
            int alightTime = alightTimes.get(i);

            // CHECK FOR AS_ROUTE FARE NETWORK
            // NB this is applied greedily, if it is cheaper to buy separate tickets that will not be found

            // reset anything left over from previous rides
            // note that if the rides are a part of the same as_route fare network, the ride is extended in the
            // nested loop below.
            asRouteBoardStop = -1;
            // these are used to implement the functionality described in the comment above
            // useAllStopsWhenCalculatingAsRouteFareNetwork
            // They keep track of _all_ the boarding and alighting stops in a multi-vehicle journey on an as_route fare network.
            TIntSet allAsRouteFromStops = new TIntHashSet();
            TIntSet allAsRouteToStops = new TIntHashSet();

            RoaringBitmap fareNetworks = getFareNetworksForPattern(pattern);
            asRouteFareNetworks = getAsRouteFareNetworksForPattern(pattern);
            if (asRouteFareNetworks.getCardinality() > 0) {
                allAsRouteFromStops.add(boardStop);
                allAsRouteToStops.add(alightStop);
                asRouteBoardStop = boardStop;
                for (int j = i + 1; j < patterns.size(); j++) {
                    RoaringBitmap nextAsRouteFareNetworks = getAsRouteFareNetworksForPattern(patterns.get(j));
                    // can't modify asRouteFareNetworks in-place as it may have already been set as fareNetworks below
                    asRouteFareNetworks = RoaringBitmap.and(asRouteFareNetworks, nextAsRouteFareNetworks);

                    if (asRouteFareNetworks.getCardinality() > 0) {
                        // alight stop from previous ride is now a from stop and a to stop, b/c it is in the middle of the ride
                        // This is true _even if_ there is a transfer rather than another boarding at the alight stop, see
                        // the example above in the javadoc for useAllStopsWhenCalculatingAsRouteFareNetwork
                        allAsRouteFromStops.add(alightStop);

                        // board stop for new ride is now both a from and a to stop b/c is middle of ride
                        allAsRouteFromStops.add(boardStops.get(j));
                        allAsRouteToStops.add(boardStops.get(j));

                        // extend ride
                        alightStop = alightStops.get(j);
                        alightTime = alightTimes.get(j);

                        allAsRouteToStops.add(alightStop);
                        // these are the fare networks actually in use, other fare leg rules should not match
                        fareNetworks = asRouteFareNetworks;
                        i = j; // don't process this ride again
                    } else {
                        break;
                        // i is now the last ride in the as-route fare network. Process the entire thing as a single ride.
                    }
                }
            }

            // FIND THE FARE LEG RULE
            int[] fareLegRules;
            if (asRouteBoardStop != -1 && useAllStopsWhenCalculatingAsRouteFareNetwork) {
                // when useAllStopsWhenCalculatingAsRouteFareNetwork, we find the first fare leg rule that matches
                // any combination of from and to stops.
                // getMatching returns a new RoaringBitmap, okay for us to mutate
                RoaringBitmap candidateLegs = getMatching(transitLayer.fareLegRulesForFareNetworkId, fareNetworks);
                RoaringBitmap fromAreaMatch = new RoaringBitmap();
                for (TIntIterator it = allAsRouteFromStops.iterator(); it.hasNext();) {
                    // okay to use forFromStopId even though this might be a to stop, because
                    // we're treating all intermediate stops as "effective from" stops that should match from_area_id.
                    fromAreaMatch.or(transitLayer.fareLegRulesForFromStopId.get(it.next()));
                }

                RoaringBitmap toAreaMatch = new RoaringBitmap();
                for (TIntIterator it = allAsRouteToStops.iterator(); it.hasNext();) {
                    // okay to use forFromStopId even though this might be a to stop, because
                    // we're treating all intermediate stops as "effective from" stops that should match from_area_id.
                    toAreaMatch.or(transitLayer.fareLegRulesForToStopId.get(it.next()));
                }

                candidateLegs.and(fromAreaMatch);
                candidateLegs.and(toAreaMatch);

                try {
                    fareLegRules = findDominantLegRuleMatches(candidateLegs);
                    Arrays.sort(fareLegRules); // I think they should already be sorted, this may not be necessary.
                    potentialAsRouteFareLegRules = fareLegRules;
                } catch (NoFareLegRuleMatch noFareLegRuleMatch) {
                    throw new IllegalStateException("no leg rule found for as_route network!");
                }

                // it is not unexpected to find multiple matching fare leg rules here, as there may be multiple
                // fare leg rules that have the same price for different portions of the trip. As long as they provide
                // the same transfer privileges, this is okay.
            } else {
                fareLegRules = getFareLegRulesForLeg(boardStop, alightStop, fareNetworks);

                if (fareLegRules.length > 1) {
                    LOG.warn("Found multiple matching fare leg rules - routes and fares may be unstable!");
                }
            }

            int fareLegRuleIdx = fareLegRules[0];
            FareLegRuleInfo fareLegRule = transitLayer.fareLegRules.get(fareLegRuleIdx);

            // CHECK IF THERE ARE ANY TRANSFER DISCOUNTS
            if (prevFareLegRuleIdx != -1) {
                int transferRuleIdx = getFareTransferRule(prevFareLegRuleIdx, fareLegRuleIdx);
                if (transferRuleIdx == -1) {
                    // pay full fare, no transfer found
                    cumulativeFare += fareLegRule.amount;
                } else {
                    FareTransferRuleInfo transferRule = transitLayer.fareTransferRules.get(transferRuleIdx);
                    if (FareTransferType.TOTAL_COST_PLUS_AMOUNT.equals(transferRule.fare_transfer_type)) {
                        if (transferRule.amount > 0) {
                            LOG.warn("Negatively discounted transfer");
                        }
                        int fareIncrement = fareLegRule.amount + transferRule.amount;
                        if (fareIncrement < 0)
                            LOG.warn("Fare increment is negative!");
                        cumulativeFare += fareIncrement;
                    } else if (FareTransferType.FIRST_LEG_PLUS_AMOUNT.equals(transferRule.fare_transfer_type)) {
                        cumulativeFare += transferRule.amount;
                    } else {
                        throw new UnsupportedOperationException("Only total cost plus amount and first leg plus amount transfer rules are supported.");
                    }
                }
            } else {
                // pay full fare
                cumulativeFare += fareLegRule.amount;
            }

            prevFareLegRuleIdx = fareLegRuleIdx;
        }

        FaresV2TransferAllowance allowance;
        // asRouteFareNetworks contains the as route fare networks that the last leg was a part of. If multiple rides
        // have been spliced together, these will be the as-route fare networks that can be used to splice those rides,
        // even if there are additional as_route fare networks that apply to later legs of the splice; we apply as_route
        // fare networks greedily.
        if (asRouteFareNetworks != null && asRouteFareNetworks.getCardinality() > 0) {
            if (asRouteBoardStop == -1)
                throw new IllegalStateException("as route board stop not set even though there are as route fare networks.");
            // NB it is important the second argument here be sorted. This is guaranteed by RoaringBitmap.toArray()

            allowance = new FaresV2TransferAllowance(prevFareLegRuleIdx, asRouteFareNetworks.toArray(), asRouteBoardStop,
                    potentialAsRouteFareLegRules, transitLayer);
        } else {
            allowance = new FaresV2TransferAllowance(prevFareLegRuleIdx, null, -1,
                    null, transitLayer);
        }

        return new FareBounds(cumulativeFare, allowance);
    }

    /** Get the as_route fare networks for a pattern (used to merge with later rides) */
    private RoaringBitmap getAsRouteFareNetworksForPattern (int patIdx) {
        // static so we do not modify underlying bitmaps
        return RoaringBitmap.and(getFareNetworksForPattern(patIdx), transitLayer.fareNetworkAsRoute);
    }

    private RoaringBitmap getFareNetworksForPattern (int patIdx) {
        int routeIdx = transitLayer.tripPatterns.get(patIdx).routeIndex;
        return transitLayer.fareNetworksForRoute.get(routeIdx);
    }

    /**
     * Get the fare leg rule for a leg. If there is more than one, which one is returned is undefined and a warning is logged.
     * TODO handle multiple fare leg rules
     */
    private int[] getFareLegRulesForLeg (int boardStop, int alightStop, RoaringBitmap fareNetworks) {
        // find leg rules that match the fare network
        // getMatching returns a new RoaringBitmap so okay to modify
        RoaringBitmap fareNetworkMatch = getMatching(transitLayer.fareLegRulesForFareNetworkId, fareNetworks);
        fareNetworkMatch.and(transitLayer.fareLegRulesForFromStopId.get(boardStop));
        fareNetworkMatch.and(transitLayer.fareLegRulesForToStopId.get(alightStop));

        try {
            return findDominantLegRuleMatches(fareNetworkMatch);
        } catch (NoFareLegRuleMatch noFareLegRuleMatch) {
            String fromStopId = transitLayer.stopIdForIndex.get(boardStop);
            String toStopId = transitLayer.stopIdForIndex.get(alightStop);
            throw new IllegalStateException("no fare leg rule found for leg from " + fromStopId + " to " + toStopId + "!");
        }
    }

    /** of all the leg rules in match, which one is dominant (lowest order)? */
    private int[] findDominantLegRuleMatches (RoaringBitmap candidateLegRules) throws NoFareLegRuleMatch {
        if (candidateLegRules.getCardinality() == 0) {
            throw new NoFareLegRuleMatch();
        } else if (candidateLegRules.getCardinality() == 1) {
            return new int[] { candidateLegRules.iterator().next() };
        } else {
            // figure out what matches, first finding the lowest order
            int lowestOrder = Integer.MAX_VALUE;
            TIntList rulesWithLowestOrder = new TIntArrayList();
            for (PeekableIntIterator it = candidateLegRules.getIntIterator(); it.hasNext();) {
                int ruleIdx = it.next();
                int order = transitLayer.fareLegRules.get(ruleIdx).order;
                if (order < lowestOrder) {
                    lowestOrder = order;
                    rulesWithLowestOrder.clear();
                    rulesWithLowestOrder.add(ruleIdx);
                } else if (order == lowestOrder) {
                    rulesWithLowestOrder.add(ruleIdx);
                }
            }

            return rulesWithLowestOrder.toArray();
        }
    }

    /**
     * get a fare transfer rule, if one exists, between fromLegRule and toLegRule
     *
     * This uses an LRU cache, because often we will be searching for the same fromLegRule and toLegRule repeatedly
     * (e.g. transfers from a Toronto bus to many other possible Toronto buses you could transfer to.)
     */
    public int getFareTransferRule (int fromLegRule, int toLegRule) {
        try {
            return fareTransferRuleCache.get(new FareTransferRuleKey(fromLegRule, toLegRule));
        } catch (ExecutionException e) {
            // should not happen. if it does, catch and re-throw.
            throw new RuntimeException(e);
        }
    }

    private int searchFareTransferRule (FareTransferRuleKey key) {
        int fromLegRule = key.fromLegGroupId;
        int toLegRule = key.toLegGroupId;
        RoaringBitmap fromLegMatch;
        if (transitLayer.fareTransferRulesForFromLegGroupId.containsKey(fromLegRule))
            // this is OR'ed with rules for fare_id_blank at build time
            fromLegMatch = transitLayer.fareTransferRulesForFromLegGroupId.get(fromLegRule);
        else if (transitLayer.fareTransferRulesForFromLegGroupId.containsKey(TransitLayer.FARE_ID_BLANK))
            // no explicit match, use implicit matches
            fromLegMatch = transitLayer.fareTransferRulesForFromLegGroupId.get(TransitLayer.FARE_ID_BLANK);
        else
            return -1;

        RoaringBitmap toLegMatch;
        if (transitLayer.fareTransferRulesForToLegGroupId.containsKey(toLegRule))
            // this is OR'ed with rules for fare_id_blank at build time
            toLegMatch = transitLayer.fareTransferRulesForToLegGroupId.get(toLegRule);
        else if (transitLayer.fareTransferRulesForToLegGroupId.containsKey(TransitLayer.FARE_ID_BLANK))
            // no explicit match, use implicit matches
            toLegMatch = transitLayer.fareTransferRulesForToLegGroupId.get(TransitLayer.FARE_ID_BLANK);
        else
            return -1;

        // use static and to create a new RoaringBitmap, don't destruct transitlayer values.
        RoaringBitmap bothMatch = RoaringBitmap.and(fromLegMatch, toLegMatch);

        if (bothMatch.getCardinality() == 0) return -1; // no discounted transfer
        else if (bothMatch.getCardinality() == 1) return bothMatch.iterator().next();
        else {
            int lowestOrder = Integer.MAX_VALUE;
            TIntList rulesWithLowestOrder = new TIntArrayList();
            for (PeekableIntIterator it = bothMatch.getIntIterator(); it.hasNext();) {
                int ruleIdx = it.next();
                int order = transitLayer.fareTransferRules.get(ruleIdx).order;
                if (order < lowestOrder) {
                    lowestOrder = order;
                    rulesWithLowestOrder.clear();
                    rulesWithLowestOrder.add(ruleIdx);
                } else if (order == lowestOrder) {
                    rulesWithLowestOrder.add(ruleIdx);
                }
            }

            if (rulesWithLowestOrder.size() > 1)
                LOG.warn("Found multiple matching fare_leg_rules with same order, results may be unstable or not find the lowest fare path!");

            return rulesWithLowestOrder.get(0);
        }
    }

    /** Used as a key into the LRU cache for fare transfer rules */
    private static class FareTransferRuleKey {
        int fromLegGroupId;
        int toLegGroupId;

        public FareTransferRuleKey (int fromLegGroupId, int toLegGroupId) {
            this.fromLegGroupId = fromLegGroupId;
            this.toLegGroupId = toLegGroupId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            FareTransferRuleKey that = (FareTransferRuleKey) o;
            return fromLegGroupId == that.fromLegGroupId &&
                    toLegGroupId == that.toLegGroupId;
        }

        @Override
        public int hashCode() {
            return Objects.hash(fromLegGroupId, toLegGroupId);
        }
    }

    @Override
    public String getType() {
        return "fares-v2";
    }

    private class NoFareLegRuleMatch extends Exception {

    }
}
