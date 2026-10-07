package com.conveyal.analysis.models;

import com.conveyal.analysis.util.JsonUtil;
import com.conveyal.r5.analyst.scenario.AddTrips;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class AddTripPatternTest {

    /// A UI modification received as JSON with two segments. Each segment is a LineString composed
    /// of two line segments. If a user enters hop times of 90 and 120 seconds, the UI converts them
    /// to these exact fractional speeds using lengths from Turf.
    private static final String ADD_TRIP_PATTERN_JSON = """
        {
          "type": "add-trip-pattern",
          "name": "Entered times",
          "transitMode": 3,
          "bidirectional": false,
          "segments": [
            {
              "stopAtStart": true,
              "stopAtEnd": true,
              "spacing": 0,
              "geometry": {
                "type": "LineString",
                "coordinates": [[2.3522, 48.8566], [2.3572, 48.8583], [2.3622, 48.8600]]
              }
            },
            {
              "stopAtStart": false,
              "stopAtEnd": true,
              "spacing": 0,
              "geometry": {
                "type": "LineString",
                "coordinates": [[2.3622, 48.8600], [2.3672, 48.8617], [2.3722, 48.8634]]
              }
            }
          ],
          "timetables": [
            {
              "_id": "tt1",
              "startTime": 25200,
              "endTime": 32400,
              "headwaySecs": 600,
              "exactTimes": false,
              "monday": true,
              "dwellTime": 30,
              "dwellTimes": [0, null, 45],
              "segmentSpeeds": [32.9396796579139, 24.703435350919012]
            }
          ]
        }
        """;

    /// The first pattern segment from above, at 823.49 m in length, with stops auto-generated
    /// every 250 m. The remainder after the last stops is 73.49 m, which is 29% of the spacing,
    /// above the 25% threshold below which the last auto-generated stop is dropped.
    /// All three auto-generated stops are kept, and the pattern has five stops total.
    private static final String AUTO_GENERATED_STOPS_JSON = """
        {
          "type": "add-trip-pattern",
          "name": "Auto-generated stops",
          "transitMode": 3,
          "bidirectional": false,
          "segments": [
            {
              "stopAtStart": true,
              "stopAtEnd": true,
              "spacing": 250,
              "geometry": {
                "type": "LineString",
                "coordinates": [[2.3522, 48.8566], [2.3572, 48.8583], [2.3622, 48.8600]]
              }
            }
          ],
          "timetables": [
            {
              "_id": "tt1",
              "startTime": 25200,
              "endTime": 32400,
              "headwaySecs": 600,
              "exactTimes": false,
              "monday": true,
              "dwellTime": 0,
              "segmentSpeeds": [32.9396796579139]
            }
          ]
        }
        """;

    /// Similar to the other auto-generated stop test, but the segment is extended to 1284.42 m,
    /// so five stops are auto-generated at 250 through 1250 m. T he remainder is only 34.42 m
    /// (13.8% of the spacing), below the threshold, so the last auto-generated stop is removed.
    private static final String AUTO_GENERATED_STOP_REMOVAL_JSON = """
        {
          "type": "add-trip-pattern",
          "name": "Auto-generated stop removal",
          "transitMode": 3,
          "bidirectional": false,
          "segments": [
            {
              "stopAtStart": true,
              "stopAtEnd": true,
              "spacing": 250,
              "geometry": {
                "type": "LineString",
                "coordinates": [[2.3522, 48.8566], [2.3572, 48.8583], [2.3622, 48.8600], [2.3678, 48.8619]]
              }
            }
          ],
          "timetables": [
            {
              "_id": "tt1",
              "startTime": 25200,
              "endTime": 32400,
              "headwaySecs": 600,
              "exactTimes": false,
              "monday": true,
              "dwellTime": 0,
              "segmentSpeeds": [32.9396796579139]
            }
          ]
        }
        """;

    /// Converting the modification to its R5 form must exactly recover the travel times the user
    /// entered. This fails if segment speeds are truncated to whole km/h anywhere on the way in
    /// (they arrive from MongoDB as fractional values), or if the backend measures segments with a
    /// distance formula other than the one the UI used to derive the speeds.
    @Test
    public void fractionalSpeedsFromEnteredTimes () throws Exception {
        AddTripPattern atp =
                (AddTripPattern) JsonUtil.objectMapper.readValue(ADD_TRIP_PATTERN_JSON, Modification.class);
        AddTrips at = atp.toR5();

        assertEquals(3, at.mode);
        assertFalse(at.bidirectional);

        assertEquals(3, at.stops.size());
        assertEquals(2.3522, at.stops.get(0).lon);
        assertEquals(48.8566, at.stops.get(0).lat);
        assertEquals(2.3622, at.stops.get(1).lon);
        assertEquals(48.8600, at.stops.get(1).lat);
        assertEquals(2.3722, at.stops.get(2).lon);
        assertEquals(48.8634, at.stops.get(2).lat);

        assertEquals(1, at.frequencies.size());
        AddTrips.PatternTimetable pt = at.frequencies.iterator().next();
        assertEquals("tt1", pt.entryId);
        assertEquals(25200, pt.startTime);
        assertEquals(32400, pt.endTime);
        assertEquals(600, pt.headwaySecs);
        assertTrue(pt.monday);
        assertFalse(pt.tuesday);

        assertArrayEquals(new int[] {90, 120}, pt.hopTimes);
        // The dwell at the middle stop was left null, so it takes the timetable's default of 30.
        assertArrayEquals(new int[] {0, 30, 45}, pt.dwellTimes);
    }

    /// Only the number of stops is checked, not their positions or hop times.
    @Test
    public void autoGeneratedStops () throws Exception {
        AddTripPattern atp =
                (AddTripPattern) JsonUtil.objectMapper.readValue(AUTO_GENERATED_STOPS_JSON, Modification.class);
        AddTrips at = atp.toR5();

        assertEquals(5, at.stops.size());
        AddTrips.PatternTimetable pt = at.frequencies.iterator().next();
        assertEquals(4, pt.hopTimes.length);
        assertEquals(5, pt.dwellTimes.length);
    }

    /// Without the removal of the auto-generated stop too close to the end stop, there would be seven stops.
    @Test
    public void autoGeneratedStopRemoval () throws Exception {
        AddTripPattern atp =
                (AddTripPattern) JsonUtil.objectMapper.readValue(AUTO_GENERATED_STOP_REMOVAL_JSON, Modification.class);
        AddTrips at = atp.toR5();

        assertEquals(6, at.stops.size());
        AddTrips.PatternTimetable pt = at.frequencies.iterator().next();
        assertEquals(5, pt.hopTimes.length);
        assertEquals(6, pt.dwellTimes.length);
    }

}
