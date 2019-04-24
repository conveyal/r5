package com.conveyal.r5.analyst.scenario;

import com.conveyal.r5.transit.TransportNetwork;
import com.fasterxml.jackson.annotation.JsonAnyGetter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * This is the R5 (worker) model object for a custom freeform JSON modification. Rather than JSON properties of a
 * specific type of modification being mapped to Java fields, this class puts them in a freeform Map from String keys
 * to Objects. Unlike all other R5 Modification subclasses, this modification type is not intended to ever be applied
 * to a TransportNetwork. It is used only in the Analysis backend. When it is serialized by the backend and sent to a
 * worker, the type property will be changed from "custom" to whatever is in the r5type property.
 *
 * This allows prototype Modification subtypes to pass through the backend to R5 workers without the backend being aware
 * of the new classes defined on the worker. This allows us to make a custom R5 worker and use it from the production
 * system, without building that custom R5 worker into the production backend just to import its Modification classes.
 *
 * Note that this modification type, although it is defined in R5, should never be applied to a transit network and
 * should never be deserialized by a worker or the backend. It only exists as a conversion target for the backend
 * class CustomModificationHolder.toR5(), and to specify the special behavior where the type code "custom" is overwritten
 * with a type code that some custom version of an R5 worker will understand (which is actually handled in
 * ModificationTypeResolver). This is why it lacks any way to set the properties, and lacks a no-arg constructor, both
 * of which would be needed for deserialization. Their absence prevents accidental misuse.
 *
 * This class could in fact be defined in analysis-backend instead of R5 to avoid confusion. It needs to be a subtype
 * of the R5 scenario modification base class, but nothing blocks us from defining such a subtype in analysis-backend.
 *
 * TODO R5 modifications are stored in MongoDB in regional analyses. CustomModificationHolders and default de/serialization target classes may clash with that, please verify.
 *
 * Created by abyrd on 2019-03-15
 */
public class CustomModificationHolder extends Modification {

    private static final Logger LOG = LoggerFactory.getLogger(CustomModificationHolder.class);

    /**
     * In this Modification type, we want to store properties in a freeform Map instead of typed fields.
     * The JsonUnwrapped annotation doesn't work on Maps, see https://stackoverflow.com/a/18043785
     * We instead use JsonAnySetter and JsonAnyGetter to store arbitrary properties in a Map.
     */
    private Map<String, Object> freeformProperties;

    /**
     * This is the method used by the serialization library to fetch the Map representing all the properties of the
     * freeform JSON modification. This behavior is specified with the JsonAnyGetter annotation. There is a corresponding
     * JsonAnySetter annotation, but we do not define such a method because this class should never be the target of a
     * deserialization action. This is because upon serialization, when sending the modification to a worker, its type
     * property is replaced with whatever is in the r5type property, so the worker will attempt to deserialize it as that
     * type of modification (not a custom modification). An instance of this class should always be created by calling
     * toR5 on the corresponding analysis-backend modification model class.
     */
    @JsonAnyGetter
    public Map<String, Object> getFreeformProperties() {
        return freeformProperties;
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
     * Constructor used to copy all arbitrary properties from the Analysis UI/Backend modification model object into
     * this R5 worker Modification object. It also serves to copy the name/comment field from the Modification base class.
     */
    public CustomModificationHolder (Map<String, Object> freeformProperties, String comment) {
        this.freeformProperties = new HashMap<>(freeformProperties);
        this.comment = comment;
    }

}
