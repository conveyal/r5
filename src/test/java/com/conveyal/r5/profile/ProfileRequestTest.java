package com.conveyal.r5.profile;

import org.junit.Before;
import org.junit.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;

import static org.junit.Assert.assertEquals;

/**
 * Some date and time related tests for profile requests.
 * Created by mabu on 2.12.2015.
 */
public class ProfileRequestTest {

    private ProfileRequest profileRequest;

    @Before
    public void setUp() throws Exception {
        profileRequest = new ProfileRequest();
        profileRequest.zoneId = ZoneId.of("Europe/Ljubljana");
    }

    @Test
    public void testEmptyGetFromTimeDate() {
        Instant expected = ZonedDateTime.now(profileRequest.zoneId).truncatedTo(ChronoUnit.DAYS).toInstant();
        Instant got = Instant.ofEpochMilli(profileRequest.getFromTimeDate());
        assertEquals(expected, got);
    }

    @Test
    public void testEmptyDate() {
        // Given a request with the time set, but NO date...
        profileRequest.fromTime = 4*3600;

        // we expect it to be the same as a request with date set to today
        ProfileRequest requestWithDate = new ProfileRequest();
        requestWithDate.zoneId = profileRequest.zoneId;
        requestWithDate.date = LocalDate.now(profileRequest.zoneId);
        requestWithDate.fromTime = profileRequest.fromTime;

        Instant expected = Instant.ofEpochMilli(requestWithDate.getFromTimeDate());
        Instant got = Instant.ofEpochMilli(profileRequest.getFromTimeDate());
        assertEquals(expected, got);
    }

    @Test
    public void testEmptyFromTime() {
        profileRequest.date = LocalDate.of(2015,10,5);
        Instant expected = ZonedDateTime.of(2015, 10, 5,0,0,0,0,profileRequest.zoneId).toInstant();
        Instant got = Instant.ofEpochMilli(profileRequest.getFromTimeDate());
        assertEquals(expected, got);
    }

    @Test
    public void testFull() {
        profileRequest.date = LocalDate.of(2015,1,5);
        profileRequest.fromTime = 6*3600+22*60;
        Instant expected = ZonedDateTime.of(2015,1,5,6,22,0,0,profileRequest.zoneId).toInstant();
        Instant got = Instant.ofEpochMilli(profileRequest.getFromTimeDate());
        assertEquals(expected, got);
    }
}
