package com.conveyal.gtfs.model;

import com.google.common.collect.Lists;

import java.io.Serializable;
import java.util.List;

/**
 * This table does not exist in GTFS. It is a join of fare_attributes and fare_rules on fare_id.
 * There should only be one fare_attribute per fare_id, but there can be many fare_rules per fare_id.
 */
public class Fare implements Serializable {
    public static final long serialVersionUID = 1L;

    public String         fare_id;
    public FareAttribute  fare_attribute;
    public List<FareRule> fare_rules = Lists.newArrayList();

    public Fare(String fare_id) {
        this.fare_id = fare_id;
    }

}
