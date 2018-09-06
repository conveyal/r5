package com.conveyal.r5.speed_test;

import com.conveyal.r5.profile.mcrr.util.ParetoDominanceFunctions;
import com.conveyal.r5.profile.mcrr.util.ParetoSortable;
import com.conveyal.r5.profile.mcrr.util.TimeUtils;
import com.conveyal.r5.speed_test.api.model.Itinerary;
import com.conveyal.r5.speed_test.api.model.Leg;

import java.util.HashSet;
import java.util.Set;

import static com.conveyal.r5.profile.mcrr.util.ParetoDominanceFunctions.createParetoDominanceFunctionArray;
import static com.conveyal.r5.profile.mcrr.util.TimeUtils.timeToStrCompact;
import static com.conveyal.r5.profile.mcrr.util.TimeUtils.timeToStrShort;

public class SpeedTestItinerary extends Itinerary implements ParetoSortable {

    private final int[] paretoValues = new int[5];

    void initParetoVector() {
        int i = 0;
        paretoValues[i++] = this.transfers;
        paretoValues[i++] = this.duration.intValue();
        paretoValues[i++] = this.walkDistance.intValue();
        paretoValues[i++] = (int) (this.endTime.getTimeInMillis() / 1000);

        //Set<String> modes = new HashSet<>();
        Set<String> agencies = new HashSet<>();

        double distanceLimit = 0;

        for (Leg leg : legs) {
            if (leg.isTransitLeg()) {
                distanceLimit += leg.distance;
            }
        }

        distanceLimit /= 3;

        for (Leg leg : legs) {
            if (leg.isTransitLeg()) {
                if (leg.distance > distanceLimit) {
                    //modes.add(leg.mode);
                    agencies.add(leg.agencyId);
                }
            }
        }
        //paretoValues[i++] = modes.hashCode();
        paretoValues[i] = agencies.hashCode();
    }

    static ParetoDominanceFunctions.Builder paretoDominanceFunctions() {
        return createParetoDominanceFunctionArray()
                .lessThen()
                .lessThen()
                .lessThen()
                .lessThen()
                //.different()
                .different();
    }

    @Override
    public int[] paretoValues() {
        return paretoValues;
    }

    @Override
    public String toString() {
        return String.format(
                "Tr: %d, duration: %s, walkDist: %5.0f, start: %s, end: %s, Details: %s",
                transfers,
                TimeUtils.timeToStrCompact(duration.intValue()),
                walkDistance,
                TimeUtils.timeToStrLong(startTime),
                TimeUtils.timeToStrLong(endTime),
                legsAsCompactString(this)
        );
    }

    /**
     * Create a compact representation of all legs in the itinerary.
     * Example:
     * <pre>
     * WALK 7:12 - 37358 - NW180 09:30 10:20 - 34523 - WALK 0:10 - 86727 - NW130 10:30 10:40 - 3551 - WALK 3:10
     * </pre>
     */
    public static String legsAsCompactString(Itinerary itinerary) {
        Integer toStop = -1;

        StringBuilder buf = new StringBuilder();
        for (Leg it : itinerary.legs) {
            Integer fromStop = it.from.stopIndex;
            if (fromStop != null && fromStop != -1 && !fromStop.equals(toStop)) {
                buf.append("- ").append(fromStop).append(" - ");
            }

            if (it.isTransitLeg()) {
                buf.append(it.mode);
                buf.append(' ');
                buf.append(it.routeShortName);
                buf.append(' ');
                buf.append(timeToStrShort(it.startTime));
                buf.append(' ');
                buf.append(timeToStrShort(it.endTime));
                buf.append(' ');
            } else {
                buf.append(it.mode);
                buf.append(' ');
                buf.append(timeToStrCompact((int) it.getDuration()));
                buf.append(' ');
            }
            toStop = it.to.stopIndex;
            if (toStop != null) {
                buf.append("- ").append(toStop).append(" - ");
            }
        }
        return buf.toString().trim();
    }
}
