package com.conveyal.r5.profile;

import com.conveyal.r5.transit.TransitLayer;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.io.Serializable;

/**
 * A fare calculator used in Analyst searches. It must be "greedy," i.e. boarding another vehicle should always cost a
 * nonnegative amount (0 is OK). The currency is not important as long as it is constant (i.e. the whole thing is in
 * dollars, yen, bitcoin or kina.
 *
 * Note that this fare calculator will be called on partial trips, both in the forward and (eventually) reverse directions.
 * Adding another ride should be monotonic - the fare should either increase or stay the same.
 */
@JsonTypeInfo(use=JsonTypeInfo.Id.NAME, include=JsonTypeInfo.As.PROPERTY, property="type")
@JsonSubTypes({
        @JsonSubTypes.Type(name = "bogota", value = BogotaGreedyFareCalculator.class),
        @JsonSubTypes.Type(name = "chicago", value = ChicagoGreedyFareCalculator.class),
        @JsonSubTypes.Type(name = "simple", value = SimpleGreedyFareCalculator.class)
})
public abstract class GreedyFareCalculator implements Serializable {
    public static final long serialVersionUID = 0L;

    public abstract int calculateFare (McRaptorSuboptimalPathProfileRouter.McRaptorState state);

    public abstract String getType ();

    public void setType (String type) {
        /* do nothing */
    }

    // injected on load
    public transient TransitLayer transitLayer;
}
