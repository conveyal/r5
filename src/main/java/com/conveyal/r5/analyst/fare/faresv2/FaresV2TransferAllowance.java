package com.conveyal.r5.analyst.fare.faresv2;

import com.conveyal.r5.analyst.fare.TransferAllowance;
import com.conveyal.r5.transit.TransitLayer;
import com.conveyal.r5.transit.faresv2.FareTransferRuleInfo;
import gnu.trove.iterator.TIntIterator;
import gnu.trove.set.TIntSet;
import gnu.trove.set.hash.TIntHashSet;
import org.roaringbitmap.PeekableIntIterator;
import org.roaringbitmap.RoaringBitmap;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.List;

/**
 * Transfer allowance for Fares V2.
 */
public class FaresV2TransferAllowance extends TransferAllowance {
    /** The transfer rules that this allowance could be the start of */
    private RoaringBitmap potentialTransferRules;

    /** need to hold on to a ref to this so that getTransferRuleSummary works - but make sure it's not accidentally serialized */
    private transient TransitLayer transitLayer;

    public FaresV2TransferAllowance (int prevFareLegRuleIdx, TransitLayer transitLayer) {
        // the value is really high to effectively disable Theorem 3.1 for now, so we don't have to actually calculate
        // the max value, at the cost of some performance.
        super(10_000_000_00, 0, 0);

        if (prevFareLegRuleIdx != -1) {
            // not at start of trip, so we may have transfers available
            // TODO this will all have to change once we properly handle chains of multiple transfers
            potentialTransferRules = FaresV2InRoutingFareCalculator.getMatching(
                    transitLayer.fareTransferRulesForFromLegGroupId, prevFareLegRuleIdx);
        } else {
            potentialTransferRules = new RoaringBitmap();
        }

        this.transitLayer = transitLayer;
    }

    @Override
    public boolean atLeastAsGoodForAllFutureRedemptions(TransferAllowance other) {
        if (other instanceof FaresV2TransferAllowance) {
            // at least as good if it provides a superset of the transfers the other does
            return potentialTransferRules.contains(((FaresV2TransferAllowance) other).potentialTransferRules);
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
