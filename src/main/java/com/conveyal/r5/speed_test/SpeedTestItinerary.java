package com.conveyal.r5.speed_test;

import com.conveyal.r5.speed_test.api.model.Itinerary;
import com.conveyal.r5.speed_test.api.model.Leg;
import com.conveyal.r5.util.ParetoDominateFunction;
import com.conveyal.r5.util.ParetoSortable;

import java.util.Calendar;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

import static com.conveyal.r5.util.ParetoDominateFunction.createParetoDominanceFunctionArray;

public class SpeedTestItinerary extends Itinerary implements ParetoSortable {

    private final int[] paretoValues = new int[4];

    void initParetoVector() {
        int i = 0;
        paretoValues[i++] = this.transfers;
        paretoValues[i++] = this.duration.intValue();
        paretoValues[i++] = this.walkDistance.intValue();

        //Set<String> modes = new HashSet<>();
        Set<String> agencies = new HashSet<>();

        double durationLimit = 0;

        for (Leg leg : legs) {
            if (leg.isTransitLeg()) {
                durationLimit += leg.distance;
            }
        }

        durationLimit /= 3;

        for (Leg leg : legs) {
            if(leg.isTransitLeg()) {
                if (leg.distance > durationLimit) {
                    //modes.add(leg.mode);
                    agencies.add(leg.agencyId);
                }
            }
        }
        //paretoValues[i++] = modes.hashCode();
        paretoValues[i] = agencies.hashCode();
    }

    static ParetoDominateFunction.Builder paretoDominanceFunctions() {
        return createParetoDominanceFunctionArray()
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
        StringBuilder routesBuf = new StringBuilder();
        Set<String> modes = new TreeSet<>();
        Set<String> agencies = new TreeSet<>();
        boolean append = false;

        for (Leg it : legs) {
            if(it.isTransitLeg()) {
                modes.add(it.mode);
                agencies.add(it.agencyName);

                if(append) routesBuf.append(" -> ");
                append = true;
                routesBuf.append(it.routeShortName);
            }
        }
        return String.format("%2d %5d %5.0f %-16s %-40s %-24s %s %s", transfers, duration/60, walkDistance, modes, agencies, routesBuf, toStr(startTime), toStr(endTime));
    }
    public static String toStringHeader() {
        return String.format("%2s %5s %5s %-16s %-40s %-24s %s", "TF", "Time", "Walk", "Modes", "Agencies", "Routes", "start end");
    }

    private String toStr(Calendar c) {
        return String.format("%02d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE));
    }
}
