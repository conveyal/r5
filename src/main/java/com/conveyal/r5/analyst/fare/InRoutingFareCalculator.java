package com.conveyal.r5.analyst.fare;

import com.conveyal.gtfs.model.Fare;
import com.conveyal.r5.profile.FastRaptorWorker;
import com.conveyal.r5.profile.McRaptorSuboptimalPathProfileRouter.McRaptorState;
import com.conveyal.r5.profile.ProfileRequest;
import com.conveyal.r5.transit.TransitLayer;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.io.Serializable;
import java.util.Collection;
import java.util.Map;
import java.util.function.ToIntFunction;

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
        @JsonSubTypes.Type(name = "boston", value = BostonInRoutingFareCalculator.class),
        @JsonSubTypes.Type(name = "bogota", value = BogotaInRoutingFareCalculator.class),
        @JsonSubTypes.Type(name = "chicago", value = ChicagoInRoutingFareCalculator.class),
        @JsonSubTypes.Type(name = "simple", value = SimpleInRoutingFareCalculator.class),
        @JsonSubTypes.Type(name = "bogota-mixed", value = BogotaMixedInRoutingFareCalculator.class)
})
public abstract class InRoutingFareCalculator implements Serializable {
    public static final long serialVersionUID = 0L;

    public abstract FareBounds calculateFare (McRaptorState state, int maxClockTime);

    public abstract String getType ();

    public void setType (String type) {
        /* do nothing */
    }

    public Map<String, Fare> gtfsFares;

    // injected on load
    public transient TransitLayer transitLayer;

    public static Collater getCollator (ProfileRequest request){
        return (states, maxClockTime) -> {
            McRaptorState best = null;
            for (McRaptorState state : states) {
                // check if this state falls below the fare cutoff.
                // We generally try not to impose cutoffs at calculation time, but leaving two free cutoffs creates a grid
                // of possibilities that is too large to be stored.
                FareBounds fareAtState = request.inRoutingFareCalculator.calculateFare(state, maxClockTime);

                if (fareAtState.cumulativeFarePaid > request.maxFare) {
                    continue;
                }

                if (best == null || state.time < best.time) best = state;
            }
            if (best != null){
                return best.time;
            } else {
                return FastRaptorWorker.UNREACHED;
            }
        };
    }

    public class StandardFareBounds extends FareBounds {
        int farePaid;
        int maxFarePrivilege;

        public int getFarePaid(){
            return this.farePaid;
        }

        public int getAvailableTransferValue(){
            return this.maxFarePrivilege;
        }

        public boolean isFarePrivilegeComparableTo(FareBounds other) {
            return true;
        }

        public StandardFareBounds(int farePaid){
            super(farePaid, new TransferAllowance());
        }

    }

    public interface Collater {
        int collate (Collection<McRaptorState> states, int maxClockTime);
    }
}
