package com.conveyal.analysis.persistence;

import org.bson.BsonReader;
import org.bson.BsonWriter;
import org.bson.codecs.Codec;
import org.bson.codecs.DecoderContext;
import org.bson.codecs.EncoderContext;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

class LocalDateCodec implements Codec<LocalDate> {
    @Override
    public LocalDate decode(BsonReader reader, DecoderContext decoderContext) {
        return LocalDate.parse(reader.readString(), DateTimeFormatter.ISO_LOCAL_DATE);
    }

    @Override
    public void encode(BsonWriter writer, LocalDate value, EncoderContext encoderContext) {
        if (value != null) {
            writer.writeString(value.format(DateTimeFormatter.ISO_LOCAL_DATE));
        }
    }

    @Override
    public Class<LocalDate> getEncoderClass() {
        return LocalDate.class;
    }
}
