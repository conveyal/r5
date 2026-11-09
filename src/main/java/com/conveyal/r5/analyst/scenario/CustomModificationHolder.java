package com.conveyal.r5.analyst.scenario;

import com.conveyal.r5.transit.TransportNetwork;
import com.fasterxml.jackson.annotation.JsonAnyGetter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/// This is the R5 (worker) model object for a freeform JSON modification. Rather than mapping JSON
/// properties to the fields of a specific Java type, this class holds them in a freeform Map from
/// String keys to Objects. Unlike all other R5 Modification subclasses, this modification type is
/// not intended to be applied to a TransportNetwork. It is primarily used in the backend, as a
/// conversion target for the backend class CustomModificationHolder.toR5(), and to specify the
/// special behavior where the type code "custom" is overwritten with a type code that some custom
/// version of an R5 worker will understand (which is actually handled in ModificationTypeResolver).
/// This is why it lacks any way to set the properties, and lacks a no-arg constructor, both of
/// which would be needed for deserialization. Their absence prevents accidental misuse.
///
/// When it is serialized by the backend and sent to a worker, the type property will be changed
/// from "custom" to whatever is in the r5type property. This allows experimental Modification
/// subtypes to pass through the backend to R5 workers without the backend being aware of the new
/// classes defined on the worker. This allows us to make a custom R5 worker and use it from the
/// production system, without building that custom R5 version into the production backend just to
/// import its Modification classes.
///
/// NOTE: R5 modifications, including instances of this class, are stored in MongoDB inside regional
/// analysis records. An instance of this class is written under the type code in its r5type
/// property, so it is never read back as this class. If the backend recognizes that type code, the
/// modification is read back as the corresponding R5 class. Otherwise reading it fails. Code that
/// fetches those records excludes the stored modifications, so this seldom matters. The exception
/// is the completion of a regional analysis, when the record is read back in full immediately after
/// it is written. This should potentially be fixed by simply removing the R5 scenarios from the
/// database records, as they also exist in files.
public class CustomModificationHolder extends Modification {

    private static final Logger LOG = LoggerFactory.getLogger(CustomModificationHolder.class);

    /// In this Modification type, we want to store properties in a freeform Map instead of typed fields.
    /// The JsonUnwrapped annotation doesn't work on Maps, see https://stackoverflow.com/a/18043785
    /// We instead use JsonAnySetter and JsonAnyGetter to store arbitrary properties in a Map.
    private Map<String, Object> freeformProperties;

    /// This is the method used by the serialization library to fetch the Map representing all the
    /// properties of the freeform JSON modification. This behavior is specified with the
    /// JsonAnyGetter annotation. There is a corresponding JsonAnySetter annotation, but we do not
    /// define such a method because this class should never be the target of a deserialization
    /// action. This is because upon serialization, when sending the modification to a worker, its
    /// type property is replaced with whatever is in the r5type property, so the worker will
    /// attempt to deserialize it as that type of modification (not a custom modification). An
    /// instance of this class should always be created by calling toR5 on the corresponding
    /// analysis-backend modification model class.
    @JsonAnyGetter
    public Map<String, Object> getFreeformProperties() {
        return freeformProperties;
    }

    @Override
    public boolean apply (TransportNetwork network) {
        // Custom modifications cannot be applied to transportation networks. They just pipe data to
        // an R5 worker where it should be deserialized into a specialized modification type that
        // can be applied to the network.
        throw new UnsupportedOperationException();
    }

    @Override
    public int getSortOrder () {
        return 200;
    }

    /// Constructor used to copy all arbitrary properties from the Analysis UI/Backend modification
    /// model object into this R5 worker Modification object. It also serves to copy the
    /// name/comment field from the Modification base class.
    public CustomModificationHolder (Map<String, Object> freeformProperties, String comment) {
        this.freeformProperties = new HashMap<>(freeformProperties);
        this.comment = comment;
    }

}
