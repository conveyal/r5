package com.conveyal.r5.model.json_serialization;

import com.conveyal.r5.api.util.LegMode;
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
public class LegModeSetDeserializer extends JsonDeserializer<EnumSet<LegMode>> {
    private static final Logger LOG = LoggerFactory.getLogger(LegModeSetDeserializer.class);

    @Override
    public EnumSet<LegMode> deserialize(JsonParser jsonParser, DeserializationContext deserializationContext) throws IOException, JsonProcessingException {
        String str = jsonParser.getValueAsString();
        EnumSet<LegMode> modes = EnumSet.noneOf(LegMode.class);
        Stream.of(str.split(",")).forEach(m -> {
            LegMode mode;
            try {
                mode = LegMode.valueOf(m.toUpperCase().trim());
            } catch (IllegalArgumentException e) {
                LOG.info("LegMode {} not found, ignoring (if this is an obscure transit mode, this message is safe to ignore)", m);
                return;
            }

            modes.add(mode);
        });
        return modes;
    }
}
