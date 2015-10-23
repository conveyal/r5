package com.conveyal.r5.profile;

import junit.framework.TestCase;
import org.junit.Test;

/**
 * Test the propagated times store.
 */
public class PropagatedTimesStoreTest extends TestCase {
    /**
     * Test that changing the reachability threshold works (i.e. averages are computed properly when destinations are
     * only reachable part of the time).
     */
    @Test
    public static void testReachability () throws Exception {
        /*
        ProfileRequest pr = new ProfileRequest();
        // old default: no restrictions o
        pr.reachabilityThreshold = 0;

        PropagatedTimesStore pts = new PropagatedTimesStore(pr, 1);
        // accessible one-third of the time
        int[][] times = new int[][] {
                new int[] { 1 },
                new int[] { RaptorWorker.UNREACHED },
                new int[] { RaptorWorker.UNREACHED }
        };

        pts.setFromArray(times, PropagatedTimesStore.ConfidenceCalculationMethod.MIN_MAX);

        // it is reachable at least 0% of the time
        assertEquals(1, pts.avgs[0]);

        pr.reachabilityThreshold = 0.5f;
        pts = new PropagatedTimesStore(g, pr, 1);
        pts.setFromArray(times, PropagatedTimesStore.ConfidenceCalculationMethod.MIN_MAX);

        // it is not reachable 50% of the time
        assertEquals(RaptorWorker.UNREACHED, pts.avgs[0]);
        */
    }
}
