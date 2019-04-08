package com.conveyal.r5.analyst.fare;

/**
 * Used in InRoutingFareCalculator (and its extensions) to track how much has been paid to reach a state
 * (cumulativeFarePaid) and transfer privileges that may be redeemed at future boardings (transferAllowance). We need
 * to track the latter to protect certain states from premature pruning in Pareto search.
 */

public class FareBounds {
    public int cumulativeFarePaid; // cash already paid, cumulatively for all previous journey stages
    public TransferAllowance transferAllowance; // possible remaining value; how much you stand to lose if you
    // throw your ticket on the ground

    public FareBounds(int cumulativeFarePaid, TransferAllowance transferAllowance){
        this.cumulativeFarePaid = cumulativeFarePaid;
        this.transferAllowance = transferAllowance;
    }
}
