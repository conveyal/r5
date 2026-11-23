package com.conveyal.r5.analyst.scenario;

import com.conveyal.r5.shapefile.ShapefileMatcher;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.DatabindContext;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.jsontype.impl.TypeIdResolverBase;
import com.google.common.collect.BiMap;
import com.google.common.collect.ImmutableBiMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/// This establishes a mapping between type codes embedded in JSON objects and Java Modification types on R5 workers.
/// It is used to achieve polymorphism in serialization and deserialization to and from JSON. Almost everything here
/// can be achieved with Jackson @JsonTypeInfo and @JsonTypes annotations on the classes. But there's one exception:
/// the custom modification type that pipes arbitrary JSON through the backend to the R5 worker. In that case we need
/// to overwrite the type code "custom" with the type code we want the worker to see, which might be completely unknown
/// to the backend.
///
/// The two directions of this mapping run in different places.
///
/// Type codes are written (idFromValue) only on the backend, because workers never serialize modifications. The
/// backend writes them in three places: when it forwards a single-point task to a worker with the scenario inline,
/// when it writes the scenario file that workers fetch for a regional analysis, and when it stores a regional analysis
/// record in MongoDB.
///
/// Type codes are read (typeFromId) mainly on workers: from the body of a single-point request, and from the scenario
/// file of a regional analysis. Each worker recognizes the type codes in the table of the R5 version it was built
/// from, so a specially built worker may recognize codes that the backend and other workers do not. The backend reads
/// type codes only when it loads a regional analysis record without excluding its modifications.
///
/// A type code that is missing from the table fails in typeFromId, while the JSON is being parsed. A worker reports the
/// exception thrown there as an error on the task, covering type codes that are misspelled, that exist only on some
/// other version of the worker, or that have been removed.
public class ModificationTypeResolver extends TypeIdResolverBase {

    private static final Logger LOG = LoggerFactory.getLogger(ModificationTypeResolver.class);

    /// A mapping between all Modification types known by this worker and their JSON type codes.
    /// It is not strictly necessary since there's a deterministic mapping between CamelCase and
    /// kebab-case that could be performed on the fly, but the table seems more clear and efficient.
    private static final BiMap<String, Class<? extends Modification>> modificationTypes = new ImmutableBiMap.Builder()
            .put("add-streets", AddStreets.class)
            .put("add-trips", AddTrips.class)
            .put("adjust-dwell-time", AdjustDwellTime.class)
            .put("adjust-frequency", AdjustFrequency.class)
            .put("adjust-speed", AdjustSpeed.class)
            .put("modify-streets", ModifyStreets.class)
            .put("remove-stops", RemoveStops.class)
            .put("remove-trips", RemoveTrips.class)
            .put("reroute", Reroute.class)
            // Undocumented "custom" modifications
            .put("add-on-demand", AddOnDemand.class)
            .put("raster-cost", RasterCost.class)
            .put("road-congestion", RoadCongestion.class)
            .put("set-fare-calculator", SetFareCalculator.class)
            .put("shapefile-lts", ShapefileLts.class)
            .build();

    /// This records type names that have been removed from the table above and associates them with
    /// type codes that have replaced them, if any.
    private static final Map<String, String> REPLACED_TYPES = Map.of(
            "pickup-delay", "add-on-demand"
    );

    @Override
    public String idFromValue (Object o) {
        // For custom modifications, see if they have a specific r5 type they want to report to the worker.
        if (o instanceof CustomModificationHolder) {
            Object r5type = ((CustomModificationHolder) o).getFreeformProperties().get("r5type");
            if (r5type instanceof String) {
                return (String)r5type;
            } else {
                LOG.error("The r5type property of a custom modification was not a String. " +
                        "The R5 worker will not be able to deserialize and use the resulting R5 modification.");
                return null;
            }
        }
        // For all other modifications, just look up the corresponding type code in the table.
        return modificationTypes.inverse().get(o.getClass());
    }

    @Override
    public String idFromValueAndType (Object o, Class<?> aClass) {
        // I don't know exactly which situation this is for. It doesn't seem to be called but to be safe let's fail fast.
        throw new UnsupportedOperationException();
    }

    @Override
    public JavaType typeFromId (DatabindContext context, String id) {
        // All types of modifications that this worker understands should be in the table.
        Class<? extends Modification> modificationClass = modificationTypes.get(id);
        if (modificationClass == null) {
            String message = String.format("The modification type ID '%s' was not recognized by this R5 worker.", id);
            String replacement = REPLACED_TYPES.get(id);
            if (replacement != null) {
                message += String.format(" It has been replaced by '%s'.", replacement);
            }
            LOG.error(message);
            throw new IllegalArgumentException(message);
        }
        return context.getTypeFactory().constructType(modificationClass);
    }

    @Override
    public JsonTypeInfo.Id getMechanism () {
        return JsonTypeInfo.Id.CUSTOM;
    }

}