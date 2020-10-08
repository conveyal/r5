package com.conveyal.analysis.util;

import com.fasterxml.jackson.core.Version;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.deser.std.FromStringDeserializer;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import org.bson.types.ObjectId;

/**
 * This provides JSON serialization and deserialization of BSON IDs.
 */
public class BsonObjectIdModule extends SimpleModule {

    public BsonObjectIdModule () {
        super("BsonObjectId", new Version(1, 0, 0, null, "com.conveyal", "analysis"));
        this.addSerializer(ObjectId.class, new ToStringSerializer());
        this.addDeserializer(ObjectId.class, new FromStringDeserializer<>(ObjectId.class) {
            @Override
            protected ObjectId _deserialize(String s, DeserializationContext deserializationContext)  {
                return new ObjectId(s);
            }
        });


    }

}
