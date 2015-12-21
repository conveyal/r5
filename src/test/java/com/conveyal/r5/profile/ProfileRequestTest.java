package com.conveyal.r5.profile;

import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.*;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalUnit;
import java.util.Date;

import static org.junit.Assert.*;

/**
 * Add some date time related tests for profile reques
 *
 * Created by mabu on 2.12.2015.
 */
public class ProfileRequestTest {

    private static final Logger LOG = LoggerFactory.getLogger(ProfileRequestTest.class);

    static ProfileRequest profileRequest;

    @Before
    public void setUp() throws Exception {
        profileRequest = new ProfileRequest();

        profileRequest.setZoneId(ZoneId.of("Europe/Ljubljana"));


    }

    @Test
    public void testEmptyGetFromTimeDate() throws Exception {


        //FIXME: this actually returns yesterday:23:00 as time and date
        //To fix or not to fix depends on #37
        Instant expected = ZonedDateTime.now(profileRequest.getZoneId()).truncatedTo(ChronoUnit.DAYS).toInstant();
        Instant got = Instant.ofEpochMilli(profileRequest.getFromTimeDate());

        assertEquals(expected, got);

    }

    @Test
    public void testEmptyDate() throws Exception {
        profileRequest.fromTime = 4*3600;



        Instant expected = ZonedDateTime.now(profileRequest.getZoneId()).truncatedTo(ChronoUnit.HOURS).withHour(4).toInstant();
        Instant got = Instant.ofEpochMilli(profileRequest.getFromTimeDate());

        LOG.info("Expected:{} got:{}", expected, got);

        assertEquals(expected, got);

    }

    @Test
    public void testEmptyFromTime() throws Exception {

        profileRequest.date = new org.joda.time.LocalDate(2015,10,5);

        Instant expected = ZonedDateTime.of(2015, 10, 5,0,0,0,0,profileRequest.getZoneId()).toInstant();
        Instant got = Instant.ofEpochMilli(profileRequest.getFromTimeDate());

        assertEquals(expected, got);

    }

    @Test
    public void testFull() throws Exception {

        profileRequest.date = new org.joda.time.LocalDate(2015,1,5);
        profileRequest.fromTime = 6*3600+22*60;

        Instant expected = ZonedDateTime.of(2015,1,5,6,22,0,0,profileRequest.getZoneId()).toInstant();
        Instant got = Instant.ofEpochMilli(profileRequest.getFromTimeDate());

        assertEquals(expected, got);

    }
}