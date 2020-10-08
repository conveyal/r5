package com.conveyal.r5.model.json_serialization;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.Version;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;

import java.io.IOException;
import java.util.BitSet;

/**
 * Serialize a BitSet to an array [true, false . . .].
 */
public class BitSetSerializer extends JsonSerializer<BitSet> {

    @Override public void serialize(BitSet bitSet, JsonGenerator jsonGenerator,
            SerializerProvider serializerProvider) throws IOException, JsonProcessingException {

        jsonGenerator.writeStartArray();

        for (int i = 0; i < bitSet.length(); i++) {
            jsonGenerator.writeBoolean(bitSet.get(i));
        }

        jsonGenerator.writeEndArray();
    }

    public static SimpleModule makeModule () {
        Version moduleVersion = new Version(1, 0, 0, null, null, null);
        SimpleModule module = new SimpleModule("BitSet", moduleVersion);
        module.addSerializer(BitSet.class, new BitSetSerializer());
        module.addDeserializer(BitSet.class, new BitSetDeserializer());
        return module;
    }
}
