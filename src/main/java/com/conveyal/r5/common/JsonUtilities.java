package com.conveyal.r5.common;

import com.conveyal.geojson.GeoJsonModule;
import com.conveyal.r5.model.json_serialization.BitSetSerializer;
import com.conveyal.r5.model.json_serialization.JavaLocalDateSerializer;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Created by matthewc on 10/23/15.
 */
public class JsonUtilities {
    public static final ObjectMapper objectMapper = new ObjectMapper();
    static {
        objectMapper.registerModule(JavaLocalDateSerializer.makeModule());
        objectMapper.registerModule(new GeoJsonModule());
        objectMapper.registerModule(BitSetSerializer.makeModule());
        objectMapper.configure(JsonParser.Feature.ALLOW_COMMENTS, true);
        objectMapper.configure(JsonParser.Feature.ALLOW_UNQUOTED_FIELD_NAMES, true);
        // If we receive a JSON object containing a field that we don't recognize, fail. This should catch misspellings.
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
    }
}
