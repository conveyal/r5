package com.conveyal.gtfs.model;

import com.conveyal.gtfs.GTFSFeed;
import com.google.common.collect.ComparisonChain;
import com.google.common.collect.Ordering;

import java.io.IOException;

public class FareTransferRule extends Entity implements Comparable {
    public static final long serialVersionUID = 1L;

    public int order;
    public String from_leg_group_id;
    public String to_leg_group_id;
    public int is_symmetrical; // is_symetrical in the spec
    public int spanning_limit;
    public int duration_limit_type;
    public int duration_limit;
    public int fare_transfer_type;
    public double amount;
    public double min_amount;
    public double max_amount;
    public String currency;

    @Override
    public int compareTo(Object other) {
        FareTransferRule o = (FareTransferRule) other;
        return ComparisonChain.start()
                .compare(order, o.order)
                .compare(from_leg_group_id, o.from_leg_group_id, Ordering.natural().nullsFirst())
                .compare(to_leg_group_id, o.to_leg_group_id, Ordering.natural().nullsFirst())
                .compare(is_symmetrical, o.is_symmetrical)
                .compare(spanning_limit, o.spanning_limit)
                .compare(duration_limit_type, o.duration_limit_type)
                .compare(duration_limit, o.duration_limit)
                .compare(fare_transfer_type, o.fare_transfer_type)
                .compare(amount, o.amount)
                .compare(min_amount, o.min_amount)
                .compare(max_amount, o.max_amount)
                .compare(currency, o.currency, Ordering.natural().nullsFirst())
                .result();
    }

    public static class Loader extends Entity.Loader<FareTransferRule> {
        public Loader (GTFSFeed feed) {
            super(feed, "fare_transfer_rules");
        }

        @Override
        protected boolean isRequired() {
            return false;
        }

        @Override
        protected void loadOneRow() throws IOException {
            FareTransferRule rule = new FareTransferRule();
            rule.sourceFileLine = row + 1;
            rule.order = getIntField("order", true, 0, Integer.MAX_VALUE);
            rule.from_leg_group_id = getStringField("from_leg_group_id", false);
            rule.to_leg_group_id = getStringField("to_leg_group_id", false);

            // allow is_symmetrical to be misspelled is_symetrical due to typo in original spec
            rule.is_symmetrical = getIntField("is_symmetrical", false, 0, 1, INT_MISSING);
            if (rule.is_symmetrical == INT_MISSING) {
                rule.is_symmetrical = getIntField("is_symetrical", false, 0, 1, 0);
            }

            rule.spanning_limit = getIntField("spanning_limit", false, 0, 1, 0);
            rule.duration_limit = getIntField("duration_limit", false, 0, Integer.MAX_VALUE);
            rule.duration_limit_type = getIntField("duration_limit_type", false, 0, 2, 0);
            rule.fare_transfer_type = getIntField("fare_transfer_type", false, 0, 2, INT_MISSING);
            // can be less than zero to represent a discount (in fact, often will be)
            rule.amount = getDoubleField("amount", false, -Double.MAX_VALUE, Double.MAX_VALUE);
            rule.min_amount = getDoubleField("min_amount", false, -Double.MAX_VALUE, Double.MAX_VALUE);
            rule.max_amount = getDoubleField("max_amount", false, -Double.MAX_VALUE, Double.MAX_VALUE);
            rule.currency = getStringField("currency", false);
            
            feed.fare_transfer_rules.add(rule);
        }
    }
}
