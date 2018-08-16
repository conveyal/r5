package com.conveyal.r5.profile.mcrr.mc;

import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.stream.StreamSupport;

public class StopStateParetoSetTest {

    private static final int ANY = 3;
    private static final int ROUND_1 = 1;
    private static final int ROUND_2 = 2;
    private static final int ROUND_3 = 3;
    private static final int STOP_1 = 1;
    private static final int STOP_2 = 2;
    private static final int STOP_3 = 3;
    private static final int STOP_4 = 4;
    private static final int STOP_5 = 5;
    private static final int STOP_6 = 6;
    private StopStateParetoSet subject = StopStatesParetoSet.createState();
    private McStopState A_STATE = new McAccessStopState(999, 10);

    @Test
    public void addOneElementToSet() {
        subject.add(new McAccessStopState(1, 10));
        assertStopsInSet(1);
    }

    @Test
    public void testTimeDominance() {
        subject.add(new McAccessStopState(STOP_1, 10));
        subject.add(new McAccessStopState(STOP_2, 9));
        subject.add(new McAccessStopState(STOP_3, 9));
        subject.add(new McAccessStopState(STOP_4, 11));
        assertStopsInSet(STOP_2);
    }

    @Test
    public void testRoundDominance() {
        subject.add(new McTransferStopState(A_STATE, ROUND_1, STOP_1, 10, ANY));
        subject.add(new McTransferStopState(A_STATE, ROUND_2, STOP_2, 10, ANY));
        assertStopsInSet(STOP_1);
    }

    @Test
    public void testRoundAndTimeDominance() {
        subject.add(new McTransferStopState(A_STATE, ROUND_1, STOP_1, 9, ANY));
        subject.add(new McTransferStopState(A_STATE, ROUND_1, STOP_2, 8, ANY));

        assertStopsInSet(STOP_2);

        subject.add(new McTransferStopState(A_STATE, ROUND_2, STOP_3, 8, ANY));

        assertStopsInSet(STOP_2);

        subject.add(new McTransferStopState(A_STATE, ROUND_2, STOP_4, 7, ANY));

        assertStopsInSet(STOP_2, STOP_4);

        subject.add(new McTransferStopState(A_STATE, ROUND_3, STOP_5, 6, ANY));

        assertStopsInSet(STOP_2, STOP_4, STOP_5);

        subject.add(new McTransferStopState(A_STATE, ROUND_3, STOP_6, 6, ANY));

        assertStopsInSet(STOP_2, STOP_4, STOP_5);
    }

    @Test
    public void testTransitAndTransferDoesAffectDominance() {
        subject.add(new McAccessStopState(STOP_1, 20));
        subject.add(new McTransitStopState(A_STATE, ROUND_1, STOP_2, 10, ANY, ANY, ANY));
        subject.add(new McTransitStopState(A_STATE, ROUND_1, STOP_3, 11, ANY, ANY, ANY));
        subject.add(new McTransferStopState(A_STATE, ROUND_1, STOP_4, 8, ANY));
        subject.add(new McTransferStopState(A_STATE, ROUND_1, STOP_4, 9, ANY));
        assertStopsInSet(STOP_1, STOP_2, STOP_4);
    }

    private void assertStopsInSet(int ... expStopIndexes) {
        int[] result = StreamSupport.stream(subject.paretoSet().spliterator(), false).mapToInt(McStopState::stopIndex).sorted().toArray();
        Assert.assertEquals("Stop indexes", Arrays.toString(expStopIndexes), Arrays.toString(result));
    }
}