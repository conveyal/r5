package com.conveyal.r5.model.json_serialization;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import org.locationtech.jts.geom.LineString;

import java.io.IOException;

/**
 * A Jackson serializer module for saving LineStrings as Google encoded polylines.
 */
public class LineStringSerializer extends JsonSerializer<LineString> {

    @Override
    public void serialize(LineString lineString, JsonGenerator jsonGenerator, SerializerProvider serializerProvider)
            throws IOException, JsonProcessingException {
        jsonGenerator.writeString(PolyUtil.encode(lineString));
    }

}
