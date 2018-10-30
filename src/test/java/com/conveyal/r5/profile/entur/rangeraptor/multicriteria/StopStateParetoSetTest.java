package com.conveyal.r5.profile.entur.rangeraptor.multicriteria;

import com.conveyal.r5.profile.entur.api.AStopArrival;
import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.stream.StreamSupport;

public class StopStateParetoSetTest {
    // 08:35 in seconds
    private static final int A_TIME = ((8 * 60) + 35) * 60;
    private static final int ANY = 3;
    private static final int ROUND_1 = 1;
    private static final int ROUND_2 = 2;
    private static final int ROUND_3 = 3;

    // In this test each stop is used to identify the pareto vector - it is just one
    // ParetoSet "subject" with multiple "stops" in it. The stop have no effect on
    // the Pareto functionality.
    private static final int STOP_1 = 1;
    private static final int STOP_2 = 2;
    private static final int STOP_3 = 3;
    private static final int STOP_4 = 4;
    private static final int STOP_5 = 5;
    private static final int STOP_6 = 6;

    private McStopState A_STATE = newMcAccessStopState(999, 10);

    private StopStateParetoSet subject = StopStates.createState();

    @Test
    public void addOneElementToSet() {
        subject.add(newMcAccessStopState(1, 10));
        assertStopsInSet(1);
    }

    @Test
    public void testTimeDominance() {
        subject.add(newMcAccessStopState(STOP_1, 10));
        subject.add(newMcAccessStopState(STOP_2, 9));
        subject.add(newMcAccessStopState(STOP_3, 9));
        subject.add(newMcAccessStopState(STOP_4, 11));
        assertStopsInSet(STOP_2);
    }

    @Test
    public void testRoundDominance() {
        subject.add(newMcTransferStopState(A_STATE, ROUND_1, STOP_1, 10));
        subject.add(newMcTransferStopState(A_STATE, ROUND_2, STOP_2, 10));
        assertStopsInSet(STOP_1);
    }

    @Test
    public void testRoundAndTimeDominance() {
        subject.add(newMcTransferStopState(A_STATE, ROUND_1, STOP_1, 10));
        subject.add(newMcTransferStopState(A_STATE, ROUND_1, STOP_2, 8));

        assertStopsInSet(STOP_2);

        subject.add(newMcTransferStopState(A_STATE, ROUND_2, STOP_3, 8));

        assertStopsInSet(STOP_2);

        subject.add(newMcTransferStopState(A_STATE, ROUND_2, STOP_4, 7));

        assertStopsInSet(STOP_2, STOP_4);

        subject.add(newMcTransferStopState(A_STATE, ROUND_3, STOP_5, 6));

        assertStopsInSet(STOP_2, STOP_4, STOP_5);

        subject.add(newMcTransferStopState(A_STATE, ROUND_3, STOP_6, 6));

        assertStopsInSet(STOP_2, STOP_4, STOP_5);
    }

    @Test
    public void testTransitAndTransferDoesAffectDominance() {
        subject.add(newMcAccessStopState(STOP_1, 20));
        subject.add(new McTransitStopState(A_STATE, ROUND_1, STOP_2, 10, ANY, ANY, ANY));
        subject.add(new McTransitStopState(A_STATE, ROUND_1, STOP_3, 11, ANY, ANY, ANY));
        subject.add(newMcTransferStopState(A_STATE, ROUND_1, STOP_4, 8));
        subject.add(newMcTransferStopState(A_STATE, ROUND_1, STOP_4, 9));
        assertStopsInSet(STOP_1, STOP_2, STOP_4);
    }

    private void assertStopsInSet(int ... expStopIndexes) {
        int[] result = StreamSupport.stream(subject.spliterator(), false).mapToInt(McStopState::stopIndex).sorted().toArray();
        Assert.assertEquals("Stop indexes", Arrays.toString(expStopIndexes), Arrays.toString(result));
    }

    private static McAccessStopState newMcAccessStopState(int stop, int accessDurationInSeconds) {
        return new McAccessStopState(new AStopArrival(stop, accessDurationInSeconds), A_TIME, ANY);
    }

    private static McTransferStopState newMcTransferStopState(McStopState prev, int round, int stop, int arrivalTime) {
        return new McTransferStopState(prev, round, new AStopArrival(stop, ANY), arrivalTime);
    }
}