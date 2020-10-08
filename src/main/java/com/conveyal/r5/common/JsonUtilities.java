package com.conveyal.r5.common;

import com.conveyal.geojson.GeoJsonModule;
import com.conveyal.r5.model.json_serialization.BitSetSerializer;
import com.conveyal.r5.model.json_serialization.JavaLocalDateSerializer;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.entity.ByteArrayEntity;

/**
 * A library containing static methods for working with JSON.
 */
public abstract class JsonUtilities {

    /**
     * If we receive a JSON object containing a field that we don't recognize, fail. This should catch misspellings.
     * This is used on the broker which should always use the latest R5, ensuring that fields aren't silently
     * dropped when the broker does not support them.
     * TODO determine whether we can move the backend and worker both toward using a single objectmapper.
     */
    public static final ObjectMapper objectMapper = createBaseObjectMapper();

    /**
     * On the worker, we want to use an objectmapper that will ignore unknown properties, so that it doesn't crash
     * when an older worker is connected to a newer broker. We intentionally allow users to specify older workers
     * so they can get consistent analysis results over the life of a project.
     * TODO warn the user when the worker they've specified doesn't support a feature that's present in the scenario
     */
    public static final ObjectMapper lenientObjectMapper = createBaseObjectMapper();

    static {
        // Configure the two ObjectMappers to have opposite behavior with respect to unrecognized fields.
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
        lenientObjectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    private static ObjectMapper createBaseObjectMapper () {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(JavaLocalDateSerializer.makeModule());
        objectMapper.registerModule(new GeoJsonModule());
        objectMapper.registerModule(BitSetSerializer.makeModule());
        objectMapper.configure(JsonParser.Feature.ALLOW_COMMENTS, true);
        objectMapper.configure(JsonParser.Feature.ALLOW_UNQUOTED_FIELD_NAMES, true);
        return objectMapper;
    }

    /** Represent the supplied object as JSON in a byte array. */
    public static byte[] objectToJsonBytes (Object object) {
        try {
            return objectMapper.writeValueAsBytes(object);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    /** Convert the supplied object to an HttpEntity containing its representation as JSON. */
    public static ByteArrayEntity objectToJsonHttpEntity (Object object) {
        return new ByteArrayEntity(objectToJsonBytes(object));
    }

    /**
     * Deserializes an object of the given type from the body of the supplied Spark request.
     */
    public static <T> T objectFromRequestBody(spark.Request request, Class<T> classe) {
        try {
            return lenientObjectMapper.readValue(request.bodyAsBytes(), classe);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

}
