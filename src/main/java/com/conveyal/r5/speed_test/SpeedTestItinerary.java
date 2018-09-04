package com.conveyal.r5.speed_test;

import com.conveyal.r5.profile.mcrr.util.ParetoDominanceFunctions;
import com.conveyal.r5.profile.mcrr.util.ParetoSortable;
import com.conveyal.r5.profile.mcrr.util.TimeUtils;
import com.conveyal.r5.speed_test.api.model.Itinerary;
import com.conveyal.r5.speed_test.api.model.Leg;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static com.conveyal.r5.profile.mcrr.util.ParetoDominanceFunctions.createParetoDominanceFunctionArray;
import static com.conveyal.r5.profile.mcrr.util.TimeUtils.timeToStrCompact;
import static com.conveyal.r5.profile.mcrr.util.TimeUtils.timeToStrLong;
import static com.conveyal.r5.profile.mcrr.util.TimeUtils.timeToStrShort;

public class SpeedTestItinerary extends Itinerary implements ParetoSortable {
    private static final Map<String, String> AGENCY_NAMES_SHORT = new HashMap<>();

    static {
        AGENCY_NAMES_SHORT.put("Hedmark Trafikk FKF", "Hedmark");
        AGENCY_NAMES_SHORT.put("Indre Namdal Trafikk A/S", "I.Namdal");
        AGENCY_NAMES_SHORT.put("Møre og Romsdal fylkeskommune", "M&R");
        AGENCY_NAMES_SHORT.put("Nettbuss Travel AS", "Nettbuss");
        AGENCY_NAMES_SHORT.put("Nord-Trøndelag fylkeskommune", "N-Trøndelag");
        AGENCY_NAMES_SHORT.put("Norgesbuss Ekspress AS", "Norgesbuss");
        AGENCY_NAMES_SHORT.put("NOR-WAY Bussekspress", "NOR-WAY");
        AGENCY_NAMES_SHORT.put("Nordland fylkeskommune", "Nordland");
        AGENCY_NAMES_SHORT.put("Opplandstrafikk", "Oppland");
        AGENCY_NAMES_SHORT.put("Troms fylkestrafikk", "Troms");
        AGENCY_NAMES_SHORT.put("Vestfold Kollektivtrafikk as", "Vestfold");
        AGENCY_NAMES_SHORT.put("Østfold fylkeskommune", "Østfold");
    }


    private final int[] paretoValues = new int[4];

    void initParetoVector() {
        int i = 0;
        paretoValues[i++] = this.transfers;
        paretoValues[i++] = this.duration.intValue();
        paretoValues[i++] = this.walkDistance.intValue();

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
        List<String> stops = new ArrayList<>();
        boolean append = false;

        for (Leg it : legs) {
            if (it.isTransitLeg()) {
                modes.add(it.mode);
                agencies.add(AGENCY_NAMES_SHORT.getOrDefault(it.agencyName, it.agencyName));
                stops.add(toStr(it.startTime) + " " + it.from.stopIndex + "->" + it.to.stopIndex + " " + toStr(it.endTime));

                if (append) routesBuf.append(" > ");
                append = true;
                routesBuf.append(it.routeShortName);
            }
        }
        return String.format(
                "%2d %5d %5.0f  %5s %5s  %-16s %-30s %-28s %s",
                transfers,
                duration / 60,
                walkDistance,
                toStr(startTime),
                toStr(endTime),
                modes,
                agencies,
                routesBuf,
                stops
        );
    }


    public static String toStringHeader() {
        return String.format("%2s %5s %5s  %-5s %-5s  %-16s %-30s %-28s %s", "TF", "Time", "Walk", "Start", "End", "Modes", "Agencies", "Routes", "Stops");
    }

    /**
     * Create a compact representation of an itinerary.
     * Example:
     * <pre>
     * 2 09:22:00 10:43:10 -- WALK 7:12 [37358] NW180 09:30:00 10:20:00 [34523] WALK 0:10 [86727] NW130 10:30:00 10:40:00 [3551] WALK 3:10
     * </pre>
     */
    public String toStringCompact() {
        Integer lastStop = -1;

        StringBuilder buf = new StringBuilder();
        buf.append(timeToStrLong(startTime)).append(' ');
        buf.append(timeToStrLong(endTime)).append(' ');
        buf.append(timeToStrCompact(duration.intValue()));
        buf.append(" -- ");

        for (Leg it : legs) {
            Integer fromStop = it.from.stopIndex;
            if (fromStop != null && fromStop != -1 && !it.from.stopIndex.equals(lastStop)) {
                buf.append(" /").append(it.from.stopIndex).append("/ ");
            }

            if (it.isTransitLeg()) {
                buf.append(it.routeShortName);
                buf.append(" ");
                buf.append(timeToStrShort(it.startTime));
                buf.append(" ");
                buf.append(timeToStrShort(it.endTime));
            } else {
                buf.append(it.mode);
                buf.append(" ");
                buf.append(timeToStrCompact((int) it.getDuration()));
            }
            lastStop = it.to.stopIndex;
            if (lastStop != null) {
                buf.append(" /").append(it.to.stopIndex).append("/ ");
            }
        }
        return buf.toString();
    }

    private static String toStr(Calendar c) {
        return TimeUtils.timeToStrShort(c);
    }

}
