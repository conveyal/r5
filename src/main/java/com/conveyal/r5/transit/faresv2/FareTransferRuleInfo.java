package com.conveyal.r5.transit.faresv2;

import com.conveyal.gtfs.model.FareTransferRule;

import java.io.Serializable;

/**
 * Information about a fare transfer rule.
 */
public class FareTransferRuleInfo implements Serializable {
    public static final long serialVersionUID = 1L;

    public int order;
    public int spanning_limit;
    public int duration_limit;
    public DurationLimitType duration_limit_type;
    public FareTransferType fare_transfer_type;
    public int amount;

    public FareTransferRuleInfo (FareTransferRule rule) {
        if (!Currency.scalarForCurrency.containsKey(rule.currency))
            throw new IllegalStateException("No scalar value specified in scalarForCurrency for currency " + rule.currency);
        int currencyScalar = Currency.scalarForCurrency.get(rule.currency);
        if (Double.isNaN(rule.amount))
            throw new IllegalArgumentException("Amount missing from fare_leg_rule (min_amount/max_amount not supported!");
        amount = (int) (rule.amount * currencyScalar);
        order = rule.order;
        spanning_limit = rule.spanning_limit;
        duration_limit = rule.duration_limit;
        duration_limit_type = DurationLimitType.forGtfs(rule.duration_limit_type);
        fare_transfer_type = FareTransferType.forGtfs(rule.fare_transfer_type);
    }

    public static enum DurationLimitType {
        FIRST_DEPARTURE_LAST_ARRIVAL,
        FIRST_DEPARTURE_LAST_DEPARTURE,
        FIRST_ARRIVAL_LAST_DEPARTURE;

        public static DurationLimitType forGtfs (int i) {
            switch (i) {
                case 0:
                    return FIRST_DEPARTURE_LAST_ARRIVAL;
                case 1:
                    return FIRST_DEPARTURE_LAST_DEPARTURE;
                case 2:
                    return FIRST_ARRIVAL_LAST_DEPARTURE;
                default:
                    throw new IllegalArgumentException("invalid GTFS duration_limit_type");
            }
        }
    }

    public static enum FareTransferType {
        FIRST_LEG_PLUS_AMOUNT,
        TOTAL_COST_PLUS_AMOUNT,
        MOST_EXPENSIVE_PLUS_AMOUNT;

        public static FareTransferType forGtfs (int i) {
            switch (i) {
                case 0:
                    return FIRST_LEG_PLUS_AMOUNT;
                case 1:
                    return TOTAL_COST_PLUS_AMOUNT;
                case 2:
                    return MOST_EXPENSIVE_PLUS_AMOUNT;
                default:
                    throw new IllegalArgumentException("invalid GTFS fare_transfer_type");
            }
        }
    }
}
