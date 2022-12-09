package com.conveyal.r5.model.json_serialization;

import com.conveyal.r5.api.util.LegMode;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;

import java.io.IOException;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Serialize a mode set as MODE,MODE,MODE
 */
public class LegModeSetSerializer extends JsonSerializer<Set<LegMode>> {
    @Override
    public void serialize(Set<LegMode> modes, JsonGenerator jsonGenerator, SerializerProvider serializerProvider) throws IOException {
        String str = modes.stream().map(LegMode::toString).collect(Collectors.joining(","));
        jsonGenerator.writeString(str);
    }
}
