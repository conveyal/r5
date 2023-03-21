package com.conveyal.r5.util;

import com.axiomalaska.polylineencoder.PolylineEncoder;
import com.axiomalaska.polylineencoder.UnsupportedGeometryTypeException;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import org.locationtech.jts.geom.LineString;

import java.io.IOException;

/**
 * Serialize JTS LineString to Google encoded polyline.
 *
 * This class is the only use of dependency com.axiomalaska:polyline-encoder, and it is only used in
 * com.conveyal.r5.transitive.TransitivePattern, which is in turn only used in
 * com.conveyal.r5.transitive.TransitiveNetwork, which is in turn only used in
 * com.conveyal.r5.analyst.cluster.AnalysisWorker#saveTauiMetadata.
 * That dependency has required maintainance on a few occasions and was hosted at a repo outside Maven Central which has
 * become unavailable on a few occations. We have copied the artifact to our S3-backed Conveyal Maven repo.
 */
public class EncodedPolylineSerializer extends JsonSerializer<LineString> {

    @Override
    public void serialize(LineString lineString, JsonGenerator jsonGenerator, SerializerProvider serializerProvider) throws IOException, JsonProcessingException {
        try {
            String points = PolylineEncoder.encode(lineString).getPoints();
            jsonGenerator.writeString(points);
        } catch (UnsupportedGeometryTypeException e) {
            throw new RuntimeException(e);
        }
    }

}
