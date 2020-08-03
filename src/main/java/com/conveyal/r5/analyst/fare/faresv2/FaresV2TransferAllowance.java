package com.conveyal.r5.analyst.fare.faresv2;

import com.conveyal.r5.analyst.fare.TransferAllowance;
import com.conveyal.r5.transit.TransitLayer;
import com.conveyal.r5.transit.faresv2.FareLegRuleInfo;
import com.conveyal.r5.transit.faresv2.FareTransferRuleInfo;
import org.roaringbitmap.PeekableIntIterator;
import org.roaringbitmap.RoaringBitmap;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Transfer allowance for Fares V2.
 */
public class FaresV2TransferAllowance extends TransferAllowance {
    /** The transfer rules that this allowance could be the start of */
    private RoaringBitmap potentialTransferRules;

    /** need to hold on to a ref to this so that getTransferRuleSummary works - but make sure it's not accidentally serialized */
    private transient TransitLayer transitLayer;

    /** as_route fare networks we are currently in, that could have routes extended */
    public int[] asRouteFareNetworks;

    /** Where we boarded the as_route fare networks */
    public int asRouteFareNetworksBoardStop = -1;

    /**
     * When useAllStopsWhenCalculatingAsRouteFareNetwork = true in FaresV2InRoutingFareCalculator, we need to
     * differentiate trips inside as_route fare networks by the fare zones they use, not just the board stop.
     *
     * potentialAsRouteFareLegRules should contain all the fare leg rules that could apply to the as_route trip. More
     * specifically, two conditions must hold for potentialAsRouteFareLegs: 1) it must include the fare leg rule that
     * represents the full extent of the trip (e.g. for a B - C - A trip, it must include C - A), and 2) it must _not_ include
     * any fare leg rule larger than the full extent of the trip. (Full extent imagines the fare zones as being linear,
     * but in Toronto where this useAllStopsWhenCalculatingAsRouteFareNetwork we are taking "full extent" to mean "most
     * expensive", and though space is two-dimensional, money is one-dimensional.) If the most expensive fare leg rules
     * are in this array, then the same logic should apply - two routes that have the same most expensive fare leg rule(s)
     * cover the same extents.
     *
     * To keep this tractable, the array only retains the fare leg rules with the lowest order
     * (as set in fare_leg_rules.txt). Thus, the fare leg rulefor the full extent must always have the
     * lowest order in the feed. This is generally true anyways, since the fare leg rule with the lowest
     * order will be the one returned, but if A-B and A-C are the same price, you might be sloppy
     * and assign order randomly for these two fare pairs. But to get proper transfer allowance domination logic, A-C
     * must have a lower order or the same order as A-B. If they have the same fare and transfer privileges, routing will
     * not be affected if they have the same order - the A-B fare leg may be used when A-C should really be, but that
     * will not affect the result as the two fare legs are equivalent, and A-C will still appear in this array and satisfy
     * the two conditions above.
     *
     * If both of these conditions hold, then the two journeys with the same potentialAsRouteFareRules cover the same
     * territory and can be considered equivalent.
     *
     * Proof:
     * Suppose without loss of generality that fare leg rules 1 and 2 are the most expensive ("full extent") for journey
     * Q, and R.potentialAsRouteFareLegRules == Q.potentialAsRouteFareRules.
     *
     * 1. By condition 1, if 1 and 2 are the most extensive/expensive fare leg rules for journey Q, then they must appear in
     * Q.potentialAsRouteFareRules.
     * 2. By condition 2, no other more expensive/extensive fare leg rules can appear in Q.potentialAsRouteFareRules.
     * 3. If Q.potentialAsRouteFareRules == R.potentialAsRouteFareRules, then 1 and 2 are the most expensive/extensive
     *    fare leg rules for R as well as Q.
     * 4. Q and R are thus equally extensive/expensive.
     * Q.E.D.
     */
    private int[] potentialAsRouteFareLegRules;

    /**
     * 
     * @param prevFareLegRuleIdx
     * @param asRouteFareNetworks The as route fare networks this leg is in. Must be sorted.
     * @param potentialAsRouteFareLegRules potential fare rules for an as_route network; see comment in javadoc on
     *                                     this.potentialAsRouteFareLegs. must be sorted.
     * @param asRouteFareNetworksBoardStop
     * @param transitLayer
     */
    public FaresV2TransferAllowance (int prevFareLegRuleIdx, int[] asRouteFareNetworks, int asRouteFareNetworksBoardStop,
                                     int[] potentialAsRouteFareLegRules, TransitLayer transitLayer) {
        // the value is really high to effectively disable Theorem 3.1 for now, so we don't have to actually calculate
        // the max value, at the cost of some performance.
        super(10_000_000_00, 0, 0);

        this.asRouteFareNetworks = asRouteFareNetworks;
        this.asRouteFareNetworksBoardStop = asRouteFareNetworksBoardStop;
        this.potentialAsRouteFareLegRules = potentialAsRouteFareLegRules;

        if (prevFareLegRuleIdx != -1 && transitLayer.fareTransferRulesForFromLegGroupId.containsKey(prevFareLegRuleIdx)) {
            // not at start of trip, so we may have transfers available
            potentialTransferRules = transitLayer.fareTransferRulesForFromLegGroupId.get(prevFareLegRuleIdx);
        } else {
            potentialTransferRules = new RoaringBitmap();
        }

        this.transitLayer = transitLayer;
    }

    @Override
    public boolean atLeastAsGoodForAllFutureRedemptions(TransferAllowance other) {
        if (other instanceof FaresV2TransferAllowance) {
            FaresV2TransferAllowance o = (FaresV2TransferAllowance) other;
            boolean exactlyOneHasAsRoute = asRouteFareNetworks == null && o.asRouteFareNetworks != null ||
                        asRouteFareNetworks != null && o.asRouteFareNetworks == null;
            // either could be better, one is in as_route network. Could assume that being in an as_route network is always
            // better than not, conditional on same potentialTransferRules, but this is not always the case.
            // see bullet 4 at https://indicatrix.org/post/regular-2-for-you-3-when-is-a-discount-not-a-discount/
            if (exactlyOneHasAsRoute) return false;

            // if both have as route, only comparable if they have the same as route networks and same board stop.
            boolean bothHaveAsRoute = asRouteFareNetworks != null && o.asRouteFareNetworks != null;
            if (bothHaveAsRoute) {
                // asRouteFareNetworks is always sorted since it comes from RoaringBitset.toArray, so simple Arrays.equal
                // comparison is fine. potentialAsRouteFareLegs is also always sorted.
                if (asRouteFareNetworksBoardStop != o.asRouteFareNetworksBoardStop ||
                        // both will be null if useAllStopsWhenCalculatingAsRouteFareNetwork is false, so Objects.equals
                        // will return true and these conditions will be a no-op.
                        // See proof in javadoc for potentialAsRouteFareLegRules for why this comparison is correct
                        !Arrays.equals(potentialAsRouteFareLegRules, o.potentialAsRouteFareLegRules) ||
                        !Arrays.equals(asRouteFareNetworks, o.asRouteFareNetworks)) return false;
            }

            // at least as good if it provides a superset of the transfers the other does
            return potentialTransferRules.contains(o.potentialTransferRules);
        } else {
            throw new IllegalArgumentException("mixing of transfer allowance types!");
        }
    }

    @Override
    public TransferAllowance tightenExpiration(int maxClockTime) {
        return this; // expiration time not implemented
    }

    /**
     * Displaying a bunch of ints in the debug interface is going to be impossible to debug. Instead, generate an
     * on the fly string representation. This is not called in routing so performance isn't really an issue.
     */
    public List<String> getTransferRuleSummary () {
        if (transitLayer == null) return IntStream.of(potentialTransferRules.toArray())
                .mapToObj(Integer::toString)
                .collect(Collectors.toList());

        List<String> transfers = new ArrayList<>();

        for (PeekableIntIterator it = potentialTransferRules.getIntIterator(); it.hasNext();) {
            int transferRuleIdx = it.next();
            FareTransferRuleInfo info = transitLayer.fareTransferRules.get(transferRuleIdx);
            transfers.add(info.from_leg_group_id + " " + info.to_leg_group_id);
        }

        transfers.sort(Comparator.naturalOrder());

        return transfers;
    }

    public List<String> getPotentialAsRouteFareLegRules () {
        if (potentialAsRouteFareLegRules == null) return null;
        List<String> result = IntStream.of(potentialAsRouteFareLegRules)
            .mapToObj(legRule -> {
                if (transitLayer == null) return Integer.toString(legRule);
                FareLegRuleInfo info = transitLayer.fareLegRules.get(legRule);
                if (info.leg_group_id != null) return info.leg_group_id;
                else return Integer.toString(legRule);
            })
            .collect(Collectors.toList());

        result.sort(Comparator.naturalOrder());

        return result;
    }
}
