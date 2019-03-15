package com.conveyal.r5.analyst.scenario;

import com.conveyal.r5.transit.TransportNetwork;
import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * This is the R5 (worker) model object for a custom freeform JSON modification.
 * It is not intended to ever be applied to a network - when it is serialized by the backend and sent to a worker,
 * the type code will be changed from "custom" to whatever is in the r5type property.
 *
 * This allows prototype Modification subtypes to pass through the backend to workers without the backend being aware
 * of the new classes defined on the worker. This allows us to make a custom R5 worker and use it from the production
 * system, without building that custom R5 worker into the production backend.
 *
 * Note that this modification type, although it is defined in R5, should never be applied to a transit network and
 * should never be deserialized by a worker or the backend. It only exists as a conversion target for the backend
 * class CustomModification.toR5(), and to specify the special behavior where the type code "custom" is overwritten
 * with a type code that some custom version of an R5 worker will understand.
 * FIXME the class could in fact be defined in Analysis-backend instead of R5 to avoid confusion - it just needs to be a subtype of R5 scenario modificaition base class.
 * FIXME R5 modifications are stored in MongoDB in regional analyses. CustomModifications and default de/serialization may clash with that.
 *
 * Created by abyrd on 2019-03-15
 */
public class CustomModification extends Modification {

    private static final Logger LOG = LoggerFactory.getLogger(CustomModification.class);

    /**
     * JsonUnwrapped doesn't work on Maps, see https://stackoverflow.com/a/18043785
     * We instead use JsonAnySetter and JsonAnyGetter to store arbitrary properties in a map.
     */
    private Map<String, Object> freeformProperties;

    @JsonAnyGetter
    public Map<String, Object> getFreeformProperties() {
        return freeformProperties;
    }

    @JsonAnySetter
    public void setFreeformProperties(String key, Object value) {
        // This should perhaps throw an UnsupportedOperationException because we should never deserialize these objects.
        // Actually we could just not define a JsonAnySetter, and explain why in a comment.
        this.freeformProperties.put(key, value);
    }

    @Override
    public boolean apply (TransportNetwork network) {
        // Custom modifications cannot be applied to transportation networks. They just pipe data to an R5 worker
        // where it should be deserialized into a specialized modification type that can be applied to the network.
        throw new UnsupportedOperationException();
    }

    @Override
    public int getSortOrder () {
        return 200;
    }

    /**
     * Constructor used to copy all arbitrary properties from the Analysis UI/Backend model object into this R5 worker
     * model object.
     */
    public CustomModification (Map<String, Object> freeformProperties) {
        this.freeformProperties = new HashMap<>(freeformProperties);
    }

    /**
     * No-arg constructor for deserialization.
     * This type should never actually be deserialized.
     * We might be able to just remove this constructor and explain why it's not needed in the other constructor Javadoc.
     */
    public CustomModification () {
        throw new UnsupportedOperationException();
        // this.freeformProperties = new HashMap<>();
    }

}
