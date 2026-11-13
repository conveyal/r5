package com.conveyal.r5.analyst;

import com.conveyal.r5.profile.StreetMode;
import gnu.trove.map.TIntIntMap;
import gnu.trove.map.hash.TIntIntHashMap;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/// Tests of merging access times to stops.
class StreetTimesAndModesTest {

    private static TIntIntMap times (int stop, int seconds) {
        TIntIntMap times = new TIntIntHashMap();
        times.put(stop, seconds);
        return times;
    }

    @Test
    void flagFollowsWinner () {
        StreetTimesAndModes best = new StreetTimesAndModes();
        best.update(times(1, 600), StreetMode.WALK, false);
        best.update(times(1, 300), StreetMode.WALK, true);
        best.update(times(2, 300), StreetMode.WALK, true);
        best.update(times(2, 200), StreetMode.WALK, false);
        assertTrue(best.streetTimesAndModes.get(1).onDemand, "The faster on-demand arrival should win stop 1.");
        assertEquals(300, best.streetTimesAndModes.get(1).time);
        assertFalse(best.streetTimesAndModes.get(2).onDemand, "The faster plain walk should win stop 2.");
        assertEquals(200, best.streetTimesAndModes.get(2).time);
    }

    /// Otherwise identical arrivals with and without on-demand are distinct.
    /// Path grouping should not conflate them.
    @Test
    void onDemandDistinguishesArrivals () {
        var plain = new StreetTimesAndModes.StreetTimeAndMode(300, StreetMode.WALK);
        var ridden = new StreetTimesAndModes.StreetTimeAndMode(300, StreetMode.WALK, true);
        assertNotEquals(plain, ridden);
        assertTrue(ridden.toString().contains("ON_DEMAND"), ridden.toString());
        assertFalse(plain.toString().contains("ON_DEMAND"), plain.toString());
    }

}
