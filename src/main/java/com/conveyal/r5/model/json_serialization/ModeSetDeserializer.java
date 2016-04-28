package com.conveyal.r5.model.json_serialization;

import com.conveyal.r5.profile.StreetMode;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.EnumSet;
import java.util.stream.Stream;

/**
 * Deserialize modesets in the form MODE,MODE,MODE
 */
public class ModeSetDeserializer extends JsonDeserializer<EnumSet<StreetMode>> {
    private static final Logger LOG = LoggerFactory.getLogger(ModeSetDeserializer.class);

    @Override
    public EnumSet<StreetMode> deserialize(JsonParser jsonParser, DeserializationContext deserializationContext) throws IOException, JsonProcessingException {
        String str = jsonParser.getValueAsString();
        EnumSet<StreetMode> streetModes = EnumSet.noneOf(StreetMode.class);
        Stream.of(str.split(",")).forEach(m -> {
            StreetMode streetMode;
            try {
                streetMode = StreetMode.valueOf(m.toUpperCase().trim());
            } catch (IllegalArgumentException e) {
                LOG.info("Mode {} not found, ignoring (if this is an obscure transit mode, this message is safe to ignore)", m);
                return;
            }

            streetModes.add(streetMode);
        });
        return streetModes;
    }
}
