package com.conveyal.r5.common;

import com.conveyal.geojson.GeoJsonModule;
import com.conveyal.r5.analyst.cluster.WorkerStatus;
import com.conveyal.r5.model.json_serialization.BitSetSerializer;
import com.conveyal.r5.model.json_serialization.JavaLocalDateSerializer;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.http.entity.ByteArrayEntity;
import org.glassfish.grizzly.http.server.Request;

import java.io.IOException;
import java.io.InputStream;

/**
 * A library containing static methods for working with JSON.
 */
public abstract class JsonUtilities {
    public static final ObjectMapper objectMapper = createBaseObjectMapper();

    static {
        // If we receive a JSON object containing a field that we don't recognize, fail. This should catch misspellings.
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
    }

    public static final ObjectMapper lenientObjectMapper = createBaseObjectMapper();

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

    /** Deserializes an object of the given type from the body of the supplied request. */
    public static <T> T objectFromRequestBody(Request request, Class<T> classe) {
        try {
            return lenientObjectMapper.readValue(request.getInputStream(), classe);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

}
