package com.conveyal.r5.analyst.fare;

import com.conveyal.gtfs.model.Fare;
import com.conveyal.r5.analyst.fare.faresv2.FaresV2InRoutingFareCalculator;
import com.conveyal.r5.analyst.fare.nyc.NYCInRoutingFareCalculator;
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
 * A fare calculator used in Analyst searches. The currency is not important as long as it is integer and constant
 * (i.e. the whole thing is in cents, yen, bitcoin or kina).
 *
 *  Fare calculator need not be greedy, see https://doi.org/10.1080/13658816.2019.1605075 and summary/open-access
 *  preprint at https://indicatrix.org/post/how-transit-fares-affect-accessibility/
 */
@JsonTypeInfo(use=JsonTypeInfo.Id.NAME, include=JsonTypeInfo.As.PROPERTY, property="type")
@JsonSubTypes({
        @JsonSubTypes.Type(name = "boston", value = BostonInRoutingFareCalculator.class),
        @JsonSubTypes.Type(name = "bogota", value = BogotaInRoutingFareCalculator.class),
        @JsonSubTypes.Type(name = "chicago", value = ChicagoInRoutingFareCalculator.class),
        @JsonSubTypes.Type(name = "simple", value = SimpleInRoutingFareCalculator.class),
        @JsonSubTypes.Type(name = "bogota-mixed", value = BogotaMixedInRoutingFareCalculator.class),
        @JsonSubTypes.Type(name = "nyc", value = NYCInRoutingFareCalculator.class),
        @JsonSubTypes.Type(name = "fares-v2", value = FaresV2InRoutingFareCalculator.class)
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
