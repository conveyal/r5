package com.conveyal.gtfs.model;

import com.conveyal.gtfs.GTFSFeed;
import com.google.common.collect.ComparisonChain;
import com.google.common.collect.Ordering;
import org.apache.commons.lang3.builder.CompareToBuilder;

import java.io.IOException;
import java.util.Objects;

/**
 * A GTFS-Fares V2 FareLegRule
 */
public class FareLegRule extends Entity implements Comparable {
    public static final long serialVersionUID = 1L;

    public int order;
    public String fare_network_id;
    public String from_area_id;
    public String contains_area_id;
    public String to_area_id;
    public int is_symmetrical;
    public String from_timeframe_id;
    public String to_timeframe_id;
    public double min_fare_distance;
    public double max_fare_distance;
    public String service_id;
    public double amount;
    public double min_amount;
    public double max_amount;
    public String currency;
    public String leg_group_id;

    @Override
    public int compareTo(Object other) {
        FareLegRule o = (FareLegRule) other;
        return ComparisonChain.start()
                .compare(order, o.order)
                .compare(fare_network_id, o.fare_network_id, Ordering.natural().nullsFirst())
                .compare(from_area_id, o.from_area_id, Ordering.natural().nullsFirst())
                .compare(contains_area_id, o.contains_area_id, Ordering.natural().nullsFirst())
                .compare(to_area_id, o.to_area_id, Ordering.natural().nullsFirst())
                .compare(is_symmetrical, o.is_symmetrical)
                .compare(from_timeframe_id, o.from_timeframe_id, Ordering.natural().nullsFirst())
                .compare(to_timeframe_id, o.to_timeframe_id, Ordering.natural().nullsFirst())
                .compare(min_fare_distance, o.min_fare_distance)
                .compare(max_fare_distance, o.max_fare_distance)
                .compare(service_id, o.service_id, Ordering.natural().nullsFirst())
                .compare(amount, o.amount)
                .compare(min_amount, o.min_amount)
                .compare(max_amount, o.max_amount)
                .compare(currency, o.currency, Ordering.natural().nullsFirst())
                .compare(leg_group_id, o.leg_group_id, Ordering.natural().nullsFirst())
                .result();
     }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        FareLegRule that = (FareLegRule) o;
        return order == that.order &&
                is_symmetrical == that.is_symmetrical &&
                Double.compare(that.min_fare_distance, min_fare_distance) == 0 &&
                Double.compare(that.max_fare_distance, max_fare_distance) == 0 &&
                Double.compare(that.amount, amount) == 0 &&
                Double.compare(that.min_amount, min_amount) == 0 &&
                Double.compare(that.max_amount, max_amount) == 0 &&
                Objects.equals(fare_network_id, that.fare_network_id) &&
                Objects.equals(from_area_id, that.from_area_id) &&
                Objects.equals(contains_area_id, that.contains_area_id) &&
                Objects.equals(to_area_id, that.to_area_id) &&
                Objects.equals(from_timeframe_id, that.from_timeframe_id) &&
                Objects.equals(to_timeframe_id, that.to_timeframe_id) &&
                Objects.equals(service_id, that.service_id) &&
                Objects.equals(currency, that.currency) &&
                Objects.equals(leg_group_id, that.leg_group_id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(order, fare_network_id, from_area_id, contains_area_id, to_area_id, is_symmetrical,
                from_timeframe_id, to_timeframe_id, min_fare_distance, max_fare_distance, service_id, amount,
                min_amount, max_amount, currency, leg_group_id);
    }

    public static class Loader extends Entity.Loader<FareLegRule> {
        public Loader (GTFSFeed feed) {
            super(feed, "fare_leg_rules");
        }

        @Override
        protected boolean isRequired() {
            return false;
        }

        @Override
        protected void loadOneRow() throws IOException {
            FareLegRule rule = new FareLegRule();
            rule.sourceFileLine = row + 1;
            rule.order = getIntField("order", true, 0, Integer.MAX_VALUE);
            rule.fare_network_id = getStringField("fare_network_id", false);
            rule.from_area_id = getStringField("from_area_id", false);
            rule.to_area_id = getStringField("to_area_id", false);
            rule.contains_area_id = getStringField("contains_area_id", false);
            rule.is_symmetrical = getIntField("is_symmetrical", false, 0, 1, 0);
            rule.from_timeframe_id = getStringField("from_timeframe_id", false);
            rule.to_timeframe_id = getStringField("to_timeframe_id", false);
            rule.min_fare_distance = getDoubleField("min_fare_distance", false, 0, Double.MAX_VALUE);
            rule.max_fare_distance = getDoubleField("max_fare_distance", false, 0, Double.MAX_VALUE);
            rule.service_id = getStringField("service_id", false);
            rule.amount = getDoubleField("amount", false, 0, Double.MAX_VALUE);
            rule.min_amount = getDoubleField("min_amount", false, 0, Double.MAX_VALUE);
            rule.max_amount = getDoubleField("max_amount", false, 0, Double.MAX_VALUE);
            rule.currency = getStringField("currency", true);
            rule.leg_group_id = getStringField("leg_group_id", true);

            feed.fare_leg_rules.add(rule);
        }
    }
}
