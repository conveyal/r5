package com.conveyal.gtfs.flex;

import org.junit.jupiter.api.Test;

import java.util.BitSet;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Unit tests for pre-filtering OnDemand services. Does not load any GTFS test fixtures, but rather
/// tests the service representation and filter predicates. This filter deliberately overselects,
/// with more restrictive tests applied later at each boarding location. The earliest possible
/// boarding may include waiting for the pick-up window to begin, then waiting for the delay/offset.
/// Services not meeting any of the following conditions should be excluded:
/// - service is available on the given date
/// - pick-up must be possible before the rider's time window ends
/// - pick-up must be possible before the service's drop-off window ends (drop-off must follow pick-up)
public class OnDemandWindowTest {

    private static final int H = 3600;
    private static final int SERVICE_CODE = 3;
    private static final int TEN_MINUTES = 600;
    /// Time budget (maximum total trip duration) used for most tests, generous enough that only
    /// the tests exercising the budget bound are affected by it.
    private static final int BUDGET = 4 * H;

    private static OnDemand service (int fromStart, int fromEnd, int toEnd, double offset) {
        OnDemand od = new OnDemand();
        od.serviceCode = SERVICE_CODE;
        od.fromWindowStart = fromStart;
        od.fromWindowEnd = fromEnd;
        od.toWindowEnd = toEnd;
        od.durationOffset = offset;
        od.durationFactor = 1;
        return od;
    }

    private static BitSet activeCodes () {
        BitSet codes = new BitSet();
        codes.set(SERVICE_CODE);
        return codes;
    }

    @Test
    void serviceCalendarRespected () {
        OnDemand od = service(8 * H, 12 * H, 12 * H, TEN_MINUTES);
        assertTrue(od.canPickUpDuring(9 * H, 9 * H + BUDGET, activeCodes()));
        assertFalse(od.canPickUpDuring(9 * H, 9 * H + BUDGET, new BitSet()),
                "Service should not pick up passengers when its service code is not active.");
    }

    @Test
    void earlyRidersWait () {
        OnDemand od = service(8 * H, 12 * H, 12 * H, TEN_MINUTES);
        assertTrue(od.canPickUpDuring(6 * H, 6 * H + BUDGET, activeCodes()),
                "A rider departing before the pick-up window begins can wait for it to open.");
    }

    @Test
    void boardingImpossibleAfterPickupWindowEnds () {
        OnDemand od = service(8 * H, 12 * H, 12 * H, TEN_MINUTES);
        assertFalse(od.canPickUpDuring(12 * H, 12 * H + BUDGET, activeCodes()),
                "A service cannot pick someone up at the time its pick-up window ends.");
        assertFalse(od.canPickUpDuring(12 * H - TEN_MINUTES / 2, 12 * H - TEN_MINUTES / 2 + BUDGET, activeCodes()),
                "A pick-up offset can place earliest possible boarding past the end of the window.");
        assertTrue(od.canPickUpDuring(12 * H - 2 * TEN_MINUTES, 12 * H - 2 * TEN_MINUTES + BUDGET, activeCodes()),
                "There is still enough time within the pick-up window to accommodate the offset.");
    }

    @Test
    void serviceAlwaysAvailable () {
        OnDemand od = service(0, Integer.MAX_VALUE, Integer.MAX_VALUE, TEN_MINUTES);
        assertTrue(od.canPickUpDuring(0, BUDGET, activeCodes()));
        assertTrue(od.canPickUpDuring(2 * H, 2 * H + BUDGET, activeCodes()), "Very early in a service day.");
        assertTrue(od.canPickUpDuring(48 * H, 48 * H + BUDGET, activeCodes()), "At the end of a two-day-long service.");
    }

    @Test
    void windowOpeningBeyondTimeBudgetExcludesService () {
        OnDemand od = service(8 * H, 12 * H, 12 * H, TEN_MINUTES);
        assertFalse(od.canPickUpDuring(6 * H, 6 * H + 2 * H, activeCodes()),
                "Waiting for the window to open would consume the rider's entire time budget.");
        assertTrue(od.canPickUpDuring(6 * H, 6 * H + BUDGET, activeCodes()),
                "A larger budget leaves time to board after waiting for the window to open.");
    }

    @Test
    void windowShorterThanPickupWaitExcludesService () {
        OnDemand od = service(8 * H, 8 * H + TEN_MINUTES / 2, 12 * H, TEN_MINUTES);
        assertFalse(od.canPickUpDuring(7 * H, 7 * H + BUDGET, activeCodes()),
                "No boarding can fit in a pick-up window shorter than the pick-up wait itself.");
    }

    @Test
    void noServiceAfterDropOffEnds () {
        OnDemand od = service(8 * H, 12 * H, 10 * H, TEN_MINUTES);
        assertFalse(od.canPickUpDuring(10 * H, 10 * H + BUDGET, activeCodes()),
                "Disallow use of on-demand service when drop-off has already ended at the search time.");
        assertTrue(od.canPickUpDuring(9 * H, 9 * H + BUDGET, activeCodes()),
                "May overselect by only testing pick-up, allowing restrictive drop-off tests later.");
    }

}
