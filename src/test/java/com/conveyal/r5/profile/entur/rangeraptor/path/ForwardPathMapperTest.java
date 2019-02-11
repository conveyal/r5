package com.conveyal.r5.profile.entur.rangeraptor.path;

import com.conveyal.r5.profile.entur._shared.StopArrivalsTestData;
import com.conveyal.r5.profile.entur.api.TestTripSchedule;
import com.conveyal.r5.profile.entur.api.path.Path;
import com.conveyal.r5.profile.entur.rangeraptor.view.DestinationArrivalView;
import com.conveyal.r5.profile.entur.util.TimeUtils;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class ForwardPathMapperTest {

    @Test
    public void mapToPathForwardSearch() {
        DestinationArrivalView<TestTripSchedule> searchResult = StopArrivalsTestData.basicTripByForwardSearch();
        PathMapper<TestTripSchedule> mapper = new ForwardPathMapper<>();

        Path<TestTripSchedule> path = mapper.mapToPath(searchResult);

        Path<TestTripSchedule> expected = StopArrivalsTestData.basicTripAsPath();

        assertEquals(expected.toString(), path.toString());
        assertEquals(expected.numberOfTransfers(), path.numberOfTransfers());
        assertTime("startTime", expected.startTime(), path.startTime());
        assertTime("endTime", expected.endTime(), path.endTime());
        assertTime("totalTravelDurationInSeconds", expected.totalTravelDurationInSeconds(), path.totalTravelDurationInSeconds());
        assertEquals(expected, path);
    }

    private void assertTime(String msg, int expTime, int actualTime) {
        assertEquals(msg, TimeUtils.timeToStrLong(expTime), TimeUtils.timeToStrLong(actualTime));
    }
}