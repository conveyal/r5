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
        // it is important to round here, rather than just cast to int, because though in theory
        // rule.amount * currencyScalar should always exactly equal an integer, the subleties of floating point math
        // mean that is not always the case. For instance, consider a fare of 8.20. In double-precision floating point
        // math, 8.2 * 100 = 819.999999, and (int) (8.2 * 100) = 819, so we lose a cent. This creates havoc with, for
        // example, this trip in Toronto: https://projects.indicatrix.org/fareto-examples/?load=broken-yyz-floating-point
        // The fare for this trip should be 3.20 for TTC + 8.20 for GO + 0.80 discounted transfer to MiWay = 12.20, and
        // in fact 12.20 is the answer we get (making this issue more confusing). However, when you look at the
        // intermediate fares, we have 320 + 819 + 81, which took me a long time to figure out. First, note that the
        // 0.80 discounted transfer is implemented as 3.10 fare minus a 2.30 discount. Then note that:
        // > Math.floor(3.2 * 100) // -> 320, correct TTC fare
        // > Math.floor(8.2 * 100) // -> 819, one cent less than it should be
        // > Math.floor(3.1 * 100) - Math.floor(2.3 * 100) // -> 81, one cent _more_ than it should be
        
        // NB this was computed on a c. 2015 Macbook Pro with an Intel Core i7 (x86_64 architecture). It is possible
        // that results would be different on different CPU architectures, e.g. ARM.

        // The roundoff errors cancel here, making this a very difficult problem to understand.
        // Ideally we wouldn't be representing currency as doubles at all, but rounding should solve the problem for all
        // reasonable fare levels, as the resolution of a float

        // "In theory, theory and practice are the same thing." -- Yogi Berra
        amount = (int) Math.round(rule.amount * currencyScalar);
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
