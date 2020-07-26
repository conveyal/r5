package com.conveyal.r5.analyst.fare.faresv2;

import com.conveyal.r5.analyst.fare.TransferAllowance;
import com.conveyal.r5.transit.TransitLayer;
import com.conveyal.r5.transit.faresv2.FareTransferRuleInfo;
import gnu.trove.iterator.TIntIterator;
import gnu.trove.set.TIntSet;
import gnu.trove.set.hash.TIntHashSet;
import org.roaringbitmap.PeekableIntIterator;
import org.roaringbitmap.RoaringBitmap;

import java.util.*;

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
     * 
     * @param prevFareLegRuleIdx
     * @param asRouteFareNetworks The as route fare networks this leg is in. Must be sorted.
     * @param asRouteFareNetworksBoardStop
     * @param transitLayer
     */
    public FaresV2TransferAllowance (int prevFareLegRuleIdx, int[] asRouteFareNetworks, int asRouteFareNetworksBoardStop, TransitLayer transitLayer) {
        // the value is really high to effectively disable Theorem 3.1 for now, so we don't have to actually calculate
        // the max value, at the cost of some performance.
        super(10_000_000_00, 0, 0);

        this.asRouteFareNetworks = asRouteFareNetworks;
        this.asRouteFareNetworksBoardStop = asRouteFareNetworksBoardStop;

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
                // comparison is fine.
                if (asRouteFareNetworksBoardStop != o.asRouteFareNetworksBoardStop ||
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
    public String getTransferRuleSummary () {
        if (transitLayer == null) return potentialTransferRules.toString();

        List<String> transfers = new ArrayList<>();

        for (PeekableIntIterator it = potentialTransferRules.getIntIterator(); it.hasNext();) {
            int transferRuleIdx = it.next();
            FareTransferRuleInfo info = transitLayer.fareTransferRules.get(transferRuleIdx);
            transfers.add(info.from_leg_group_id + " " + info.to_leg_group_id);
        }

        transfers.sort(Comparator.naturalOrder());

        return String.join("\n", transfers);
    }
}
