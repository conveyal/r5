package com.conveyal.r5.model.json_serialization;

import com.conveyal.r5.profile.Mode;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.Version;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import org.joda.time.LocalDate;

import java.io.IOException;
import java.util.EnumSet;
import java.util.stream.Collectors;

/**
 * Serialize a mode set as MODE,MODE,MODE
 */
public class ModeSetSerializer extends JsonSerializer<EnumSet<Mode>> {
    @Override
    public void serialize(EnumSet<Mode> modes, JsonGenerator jsonGenerator, SerializerProvider serializerProvider) throws IOException, JsonProcessingException {
        String str = modes.stream().map(Mode::toString).collect(Collectors.joining(","));
        jsonGenerator.writeString(str);
    }
}
