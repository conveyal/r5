package com.conveyal.r5.model.json_serialization;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.Version;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Serialize localDates to YYYY-MM-DD
 */
public class JavaLocalDateSerializer extends JsonSerializer<LocalDate> {
    /** Create a module including the serializer and deserializer for local dates */
    public static SimpleModule makeModule () {
        Version moduleVersion = new Version(1, 0, 0, null, null, null);
        SimpleModule module = new SimpleModule("LocalDate", moduleVersion);
        module.addSerializer(LocalDate.class, new JavaLocalDateSerializer());
        module.addDeserializer(LocalDate.class, new JavaLocalDateDeserializer());
        return module;
    }

    @Override public void serialize(LocalDate localDate, JsonGenerator jsonGenerator,
                                    SerializerProvider serializerProvider) throws IOException, JsonProcessingException {
        jsonGenerator.writeString(localDate.format(DateTimeFormatter.ISO_LOCAL_DATE));
    }
}
