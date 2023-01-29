package com.conveyal.r5.model.json_serialization;

import com.conveyal.r5.api.util.TransitModes;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;

import java.io.IOException;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Serialize a mode set as MODE,MODE,MODE
 */
public class TransitModeSetSerializer extends JsonSerializer<Set<TransitModes>> {
    @Override
    public void serialize(Set<TransitModes> modes, JsonGenerator jsonGenerator, SerializerProvider serializerProvider) throws IOException {
        String str = modes.stream().map(TransitModes::toString).collect(Collectors.joining(","));
        jsonGenerator.writeString(str);
    }
}
