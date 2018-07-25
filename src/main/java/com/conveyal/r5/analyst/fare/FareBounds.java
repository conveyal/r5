package com.conveyal.r5.analyst.fare;

/*
Used in InRoutingFareCalculator (and its extensions) to track how much has been paid to reach a state
(cumulativeFarePaid) and transfer privileges that may be redeemed at future boardings (transferPrivilege).
We need to track the latter to protect certain states from premature pruning in Pareto search.
 */

public class FareBounds {
    public int cumulativeFarePaid; // cash already paid, cumulatively for all previous journey stages
    public TransferPrivilege transferPrivilege; // possible remaining value; how much you stand to lose if you
    // throw your ticket on the ground

    public FareBounds(int cumulativeFarePaid, TransferPrivilege transferPrivilege){
        this.cumulativeFarePaid = cumulativeFarePaid;
        this.transferPrivilege = transferPrivilege;
    }
}
