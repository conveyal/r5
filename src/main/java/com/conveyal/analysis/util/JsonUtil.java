package com.conveyal.analysis.util;

import com.conveyal.analysis.models.JsonViews;
import com.conveyal.geojson.GeoJsonModule;
import com.conveyal.r5.model.json_serialization.JavaLocalDateSerializer;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.Version;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.mongojack.internal.MongoJackModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import spark.ResponseTransformer;

public abstract class JsonUtil {

    private static final Logger LOG = LoggerFactory.getLogger(JsonUtil.class);

    public static final ObjectMapper objectMapper = getObjectMapper(JsonViews.Api.class);
    public static final ResponseTransformer toJson = objectMapper::writeValueAsString;

    public static ObjectMapper getObjectMapper (Class view) {
        return getObjectMapper(view, false);
    }

    public static ObjectMapper getObjectMapper(Class view, boolean configureMongoJack) {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new GeoJsonModule());
        objectMapper.registerModule(JavaLocalDateSerializer.makeModule());
        objectMapper.registerModule(new BsonObjectIdModule());

        if (configureMongoJack) MongoJackModule.configure(objectMapper);

        // We removed a bunch of fields from ProfileRequests which are persisted to the database
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        objectMapper.setConfig(objectMapper.getSerializationConfig().withView(view));

        return objectMapper;
    }

    public static String toJsonString (JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to write JSON.", e);
        }
    }

    public static ObjectNode objectNode () {
        return objectMapper.createObjectNode();
    }

    public static ArrayNode arrayNode () {
        return objectMapper.createArrayNode();
    }

    /// Ensure the Jackson versions loaded at runtime are ones this backend can safely use.
    /// Check two failure modes which were observed in test deployments:
    /// - jackson-core and jackson-databind from different Jackson releases (due to stray JAR files)
    /// - Jackson at or above 2.15 due to Gradle handling of transitive dependencies
    /// The cap on version numbers can be removed when mongojack and bson4jackson are no longer used for persistence.
    public static void verifyJacksonVersions () {
        Version core = com.fasterxml.jackson.core.json.PackageVersion.VERSION;
        Version databind = com.fasterxml.jackson.databind.cfg.PackageVersion.VERSION;
        LOG.info("Jackson runtime versions: core {} from {}; databind {} from {}",
                core, jarLocation(JsonFactory.class), databind, jarLocation(ObjectMapper.class));
        if (core.getMajorVersion() != databind.getMajorVersion() ||
            core.getMinorVersion() != databind.getMinorVersion()
        ) {
            throw new IllegalStateException("jackson-core %s and jackson-databind %s version mismatch."
                  .formatted(core, databind));
        }
        if (core.getMajorVersion() > 2 || core.getMinorVersion() >= 15) {
            throw new IllegalStateException(
                "Jackson %s too new for the mongojack/bson4jackson persistence layer, risk of number corruption."
                      .formatted(core));
        }
    }

    /// Report which jar a class was loaded from.
    private static String jarLocation (Class<?> clazz) {
        try {
            return clazz.getProtectionDomain().getCodeSource().getLocation().toString();
        } catch (RuntimeException e) {
            return "unknown location";
        }
    }

}
