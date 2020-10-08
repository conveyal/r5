package com.conveyal.gtfs.util.json;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.deser.std.StdScalarDeserializer;
import com.fasterxml.jackson.databind.ser.std.StdScalarSerializer;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

//import java.time.format.D;

public class JacksonSerializers {
    public static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    /** serialize local dates as noon GMT epoch times */
    public static class LocalDateStringSerializer extends StdScalarSerializer<LocalDate> {
        public LocalDateStringSerializer() {
            super(LocalDate.class, false);
        }

        @Override
        public void serialize(LocalDate ld, JsonGenerator jgen, SerializerProvider arg2) throws IOException {
            String dateString = FORMATTER.format(ld);
            jgen.writeString(dateString);
        }
    }

    /** deserialize local dates from GMT epochs */
    public static class LocalDateStringDeserializer extends StdScalarDeserializer<LocalDate> {
        public LocalDateStringDeserializer () {
            super(LocalDate.class);
        }

        @Override
        public LocalDate deserialize(JsonParser jp, DeserializationContext arg1) throws IOException {
            LocalDate date = LocalDate.parse(jp.getValueAsString(), FORMATTER);
            return date;
        }
    }

    public static class MyDtoNullKeySerializer extends StdSerializer<Object> {
        public MyDtoNullKeySerializer() {
            this(null);
        }

        public MyDtoNullKeySerializer(Class<Object> t) {
            super(t);
        }

        @Override
        public void serialize(Object nullKey, JsonGenerator jsonGenerator, SerializerProvider unused) throws IOException {
            jsonGenerator.writeFieldName("");
        }
    }

    public static final DateTimeFormatter format = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /** Serialize a local date to an ISO date (year-month-day) */
    public static class LocalDateIsoSerializer extends StdScalarSerializer<LocalDate> {
        public LocalDateIsoSerializer () {
            super(LocalDate.class, false);
        }

        @Override
        public void serialize(LocalDate localDate, JsonGenerator jsonGenerator, SerializerProvider serializerProvider) throws IOException {
            jsonGenerator.writeString(localDate.format(format));
        }
    }

    /** Deserialize an ISO date (year-month-day) */
    public static class LocalDateIsoDeserializer extends StdScalarDeserializer<LocalDate> {
        public LocalDateIsoDeserializer () {
            super(LocalDate.class);
        }

        @Override
        public LocalDate deserialize(JsonParser jsonParser, DeserializationContext deserializationContext) throws IOException {
            return LocalDate.parse(jsonParser.getValueAsString(), format);
        }

    }
}
