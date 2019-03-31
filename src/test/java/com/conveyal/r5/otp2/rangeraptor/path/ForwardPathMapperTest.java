package com.conveyal.r5.otp2.rangeraptor.path;

import com.conveyal.r5.otp2._shared.Egress;
import com.conveyal.r5.otp2._shared.StopArrivalsTestData;
import com.conveyal.r5.otp2.api.TestTripSchedule;
import com.conveyal.r5.otp2.api.path.Path;
import com.conveyal.r5.otp2.rangeraptor.transit.TransitCalculator;
import com.conveyal.r5.otp2.util.TimeUtils;
import org.junit.Test;

import static com.conveyal.r5.otp2._shared.StopArrivalsTestData.BOARD_SLACK;
import static com.conveyal.r5.otp2.rangeraptor.transit.TransitCalculator.testDummyCalculator;
import static org.junit.Assert.assertEquals;

public class ForwardPathMapperTest {
    private static final TransitCalculator CALCULATOR = testDummyCalculator(BOARD_SLACK, true);

    @Test
    public void mapToPathForwardSearch() {
        Egress egress = StopArrivalsTestData.basicTripByForwardSearch();
        DestinationArrival<TestTripSchedule> destArrival = new DestinationArrival<>(
                egress.previous(),
                egress.arrivalTime(),
                egress.additionalCost()
        );

        PathMapper<TestTripSchedule> mapper = CALCULATOR.createPathMapper();

        Path<TestTripSchedule> path = mapper.mapToPath(destArrival);

        Path<TestTripSchedule> expected = StopArrivalsTestData.basicTripAsPath();

        assertEquals(expected.toString(), path.toString());
        assertEquals(expected.numberOfTransfers(), path.numberOfTransfers());
        assertTime("startTime", expected.startTime(), path.startTime());
        assertTime("endTime", expected.endTime(), path.endTime());
        assertTime("totalTravelDurationInSeconds", expected.totalTravelDurationInSeconds(), path.totalTravelDurationInSeconds());
        assertEquals("numberOfTransfers",  expected.numberOfTransfers(), path.numberOfTransfers());
        assertEquals("cost", expected.cost(), path.cost());
        assertEquals(expected, path);
    }

    private void assertTime(String msg, int expTime, int actualTime) {
        assertEquals(msg, TimeUtils.timeToStrLong(expTime), TimeUtils.timeToStrLong(actualTime));
    }
}