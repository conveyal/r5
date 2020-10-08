package com.conveyal.r5.model.json_serialization;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/** serializer/deserializer for LocalDates to ISO dates, YYYY-MM-DD */
public class JavaLocalDateDeserializer extends JsonDeserializer<LocalDate> {

    @Override public LocalDate deserialize(JsonParser jsonParser,
                                           DeserializationContext deserializationContext)
            throws IOException, JsonProcessingException {
        return LocalDate.parse(jsonParser.getValueAsString(), DateTimeFormatter.ISO_LOCAL_DATE);
    }
}
