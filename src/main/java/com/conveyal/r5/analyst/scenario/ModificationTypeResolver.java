package com.conveyal.r5.analyst.scenario;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.DatabindContext;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.jsontype.impl.TypeIdResolverBase;
import com.google.common.collect.BiMap;
import com.google.common.collect.ImmutableBiMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This establishes a mapping between type codes embedded in JSON objects and Java Modification types on R5 workers.
 * It is used to achieve polymorphism in serialization and deserialization to and from JSON.
 * Almost everything here can be achieved with Jackson annotations @JsonTypeInfo and @JsonTypes on the classes.
 * But there's one exception: the custom modification type that pipes arbitrary JSON through the backend to the R5
 * worker. In that case we need to overwrite the type code "custom" with the type code we want the worker to see, which
 * might be completely unknown to the backend.
 *
 * Actually we might be able to perform the bulk of the class/type string mappings using those annotations, and just
 * override the behavior only on the CustomModificationHolder (with a TypeIdResolver that only handles the CustomModificationHolder
 * case). That could be done in addition to moving the R5 CustomModificationHolder to the backend, so it's clearly outside R5.
 *
 * Created by abyrd on 2019-03-15
 */
public class ModificationTypeResolver extends TypeIdResolverBase {

    private static final Logger LOG = LoggerFactory.getLogger(ModificationTypeResolver.class);

    /**
     * A mapping between all Modification types known by this worker and their JSON type codes.
     * This table is not really needed since there's a deterministic mapping between CamelCase and kebab-case here.
     * We could just perform that mapping on the fly in methods, but I suppose this is more clear and efficient.
     */
    private static final BiMap<String, Class<? extends Modification>> modificationTypes = new ImmutableBiMap.Builder()
            .put("add-streets", AddStreets.class)
            .put("add-trips", AddTrips.class)
            .put("adjust-dwell-time", AdjustDwellTime.class)
            .put("adjust-frequency", AdjustFrequency.class)
            .put("adjust-speed", AdjustSpeed.class)
            .put("modify-streets", ModifyStreets.class)
            .put("pickup-delay", PickupDelay.class)
            .put("remove-stops", RemoveStops.class)
            .put("remove-trips", RemoveTrips.class)
            .put("reroute", Reroute.class)
            .put("road-congestion", RoadCongestion.class)
            .put("set-fare-calculator", SetFareCalculator.class)
            .build();

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