package com.conveyal.analysis.util;

import com.conveyal.analysis.models.JsonViews;
import com.conveyal.geojson.GeoJsonModule;
import com.conveyal.r5.model.json_serialization.JavaLocalDateSerializer;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.mongojack.internal.MongoJackModule;
import spark.ResponseTransformer;

public abstract class JsonUtil {

    public static final ObjectMapper objectMapper = getObjectMapper(JsonViews.Api.class);
    public static final ResponseTransformer toJson = objectMapper::writeValueAsString;

    public static ObjectMapper getObjectMapper (Class view) {
        return getObjectMapper(view, false);
    }

    public static ObjectMapper getObjectMapper(Class view, boolean configureMongoJack) {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new GeoJsonModule());
        objectMapper.registerModule(JavaLocalDateSerializer.makeModule());
        objectMapper.registerModule(new BsonObjectIdModule());

        if (configureMongoJack) MongoJackModule.configure(objectMapper);

        // We removed a bunch of fields from ProfileRequests which are persisted to the database
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        objectMapper.setConfig(objectMapper.getSerializationConfig().withView(view));

        return objectMapper;
    }

}
