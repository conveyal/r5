package com.conveyal.r5.model.json_serialization;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;

import java.io.IOException;

/**
 * A Jackson serializer module for reading Google encoded polylines into LineStrings.
 */
public class LineStringDeserializer extends JsonDeserializer<LineString> {

    private static GeometryFactory geometryFactory = new GeometryFactory();

    @Override
    public LineString deserialize(JsonParser jsonParser, DeserializationContext deserializationContext)
            throws IOException, JsonProcessingException {
        return geometryFactory.createLineString(PolyUtil.decode(jsonParser.getText()));
    }

}
