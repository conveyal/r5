package com.conveyal.r5.model.json_serialization;

import com.conveyal.r5.api.util.TransitModes;
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
public class TransitModeSetDeserializer extends JsonDeserializer<EnumSet<TransitModes>> {
    private static final Logger LOG = LoggerFactory.getLogger(TransitModeSetDeserializer.class);

    @Override
    public EnumSet<TransitModes> deserialize(JsonParser jsonParser, DeserializationContext deserializationContext) throws IOException, JsonProcessingException {
        String str = jsonParser.getValueAsString();
        EnumSet<TransitModes> modes = EnumSet.noneOf(TransitModes.class);
        if (! str.isEmpty()) {
            Stream.of(str.split(",")).forEach(m -> {
                TransitModes mode;
                try {
                    mode = TransitModes.valueOf(m.toUpperCase().trim());
                } catch (IllegalArgumentException e) {
                    LOG.info("TransitModes {} not found, ignoring (if this is an obscure transit mode, this message is safe to ignore)", m);
                    return;
                }

                modes.add(mode);
            });
        }
        return modes;
    }
}
