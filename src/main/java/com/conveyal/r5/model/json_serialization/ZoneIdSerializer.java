package com.conveyal.r5.model.json_serialization;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;

import java.io.IOException;
import java.time.ZoneId;

/**
 * Serialize a ZoneId to a string
 */
public class ZoneIdSerializer extends JsonSerializer<ZoneId> {

    @Override public void serialize(ZoneId zoneId, JsonGenerator jsonGenerator,
            SerializerProvider serializerProvider) throws IOException, JsonProcessingException {
        jsonGenerator.writeString(zoneId.getId());
    }

}
