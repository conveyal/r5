package com.conveyal.r5.model.json_serialization;

import com.conveyal.r5.api.util.TransitModes;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;

import java.io.IOException;
import java.util.EnumSet;
import java.util.stream.Collectors;

/**
 * Serialize a mode set as MODE,MODE,MODE
 */
public class TransitModeSetSerializer extends JsonSerializer<EnumSet<TransitModes>> {
    @Override
    public void serialize(EnumSet<TransitModes> modes, JsonGenerator jsonGenerator, SerializerProvider serializerProvider) throws IOException, JsonProcessingException {
        String str = modes.stream().map(TransitModes::toString).collect(Collectors.joining(","));
        jsonGenerator.writeString(str);
    }
}
