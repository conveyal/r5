package com.conveyal.r5.model.json_serialization;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;

import java.io.IOException;
import java.time.ZoneId;

/**
 * Deserialize a ZoneId from a string
 */
public class ZoneIdDeserializer extends JsonDeserializer<ZoneId> {
    @Override public ZoneId deserialize(JsonParser jsonParser,
            DeserializationContext deserializationContext)
            throws IOException, JsonProcessingException {
        return ZoneId.of(jsonParser.getValueAsString());
    }
}
