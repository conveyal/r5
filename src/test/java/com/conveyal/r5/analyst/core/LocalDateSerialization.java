package com.conveyal.r5.analyst.core;

import com.conveyal.util.JsonUtils;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests correct LocalDate Serialization
 */
public class LocalDateSerialization {

    @Test
    public void testSerializationDeserialization() throws Exception {
        LocalDate date = LocalDate.now();

        String jsonDate = JsonUtils.objectMapper.writeValueAsString(date);

        LocalDate deserializedDate = JsonUtils.objectMapper.readValue(jsonDate, LocalDate.class);
        assertEquals(date, deserializedDate);


    }
}
