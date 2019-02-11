package com.conveyal.r5.profile.entur.rangeraptor.path;

import com.conveyal.r5.profile.entur._shared.StopArrivalsTestData;
import com.conveyal.r5.profile.entur.api.TestTripSchedule;
import com.conveyal.r5.profile.entur.api.path.Path;
import com.conveyal.r5.profile.entur.rangeraptor.view.DestinationArrivalView;
import org.junit.Test;

import static com.conveyal.r5.profile.entur._shared.StopArrivalsTestData.basicTripByReverseSearch;
import static com.conveyal.r5.profile.entur.util.TimeUtils.timeToStrLong;
import static org.junit.Assert.assertEquals;

public class ReversePathMapperTest {
    @Test
    public void mapToPathBackwardSearch() {
        DestinationArrivalView<TestTripSchedule> searchResult = basicTripByReverseSearch();
        PathMapper<TestTripSchedule> mapper = new ReversePathMapper<>(StopArrivalsTestData.BOARD_SLACK);

        Path<TestTripSchedule> expected = StopArrivalsTestData.basicTripAsPath();

        Path<TestTripSchedule> path = mapper.mapToPath(searchResult);


        assertEquals(expected.toString(), path.toString());
        assertEquals(expected.numberOfTransfers(), path.numberOfTransfers());
        assertTime("startTime", expected.startTime(), path.startTime());
        assertTime("endTime", expected.endTime(), path.endTime());
        assertTime("totalTravelDurationInSeconds", expected.totalTravelDurationInSeconds(), path.totalTravelDurationInSeconds());
        assertEquals(expected, path);
    }


    private void assertTime(String msg, int expTime, int actualTime) {
        assertEquals(msg, timeToStrLong(expTime), timeToStrLong(actualTime));
    }

}