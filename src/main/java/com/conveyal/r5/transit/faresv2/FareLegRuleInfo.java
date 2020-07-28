package com.conveyal.r5.transit.faresv2;

import com.conveyal.gtfs.model.FareLegRule;
import com.google.common.collect.ComparisonChain;

import java.io.Serializable;

/** contains the order and amount for a FareLegRule */
public class FareLegRuleInfo implements Serializable, Comparable {
    public static final long serialVersionUID = 1L;

    /** the cost of this fare leg rule in fixed-point currency */
    public int amount;

    /** the order of this fare leg rule */
    public int order;

    /** leg group ID of this fare leg rule */
    public String leg_group_id;

    public FareLegRuleInfo(FareLegRule rule) {
        if (!Currency.scalarForCurrency.containsKey(rule.currency))
            throw new IllegalStateException("No scalar value specified in scalarForCurrency for currency " + rule.currency);
        int currencyScalar = Currency.scalarForCurrency.get(rule.currency);
        if (Double.isNaN(rule.amount))
            throw new IllegalArgumentException("Amount missing from fare_leg_rule (min_amount/max_amount not supported!");
        amount = (int) (rule.amount * currencyScalar);
        order = rule.order;
        leg_group_id = rule.leg_group_id;
    }

    @Override
    public int compareTo(Object other) {
        FareLegRuleInfo o = (FareLegRuleInfo) other;
        return ComparisonChain.start()
                // lowest order first then lowest amount
                .compare(order, o.order)
                .compare(amount, o.amount)
                .result();
    }
}
