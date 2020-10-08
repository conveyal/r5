package com.conveyal.r5.analyst.core;

import com.conveyal.r5.common.JsonUtilities;
import org.junit.Test;

import java.time.LocalDate;

import static org.junit.Assert.assertEquals;

/**
 * Tests correct LocalDate Serialization
 */
public class LocalDateSerialization {

    @Test
    public void testSerializationDeserialization() throws Exception {
        LocalDate date = LocalDate.now();

        String jsonDate = JsonUtilities.objectMapper.writeValueAsString(date);

        LocalDate deserializedDate = JsonUtilities.objectMapper.readValue(jsonDate, LocalDate.class);
        assertEquals(date, deserializedDate);


    }
}
