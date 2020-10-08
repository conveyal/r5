package com.conveyal.r5.analyst.fare.nyc;

import com.conveyal.r5.transit.TransitLayer;
import com.conveyal.r5.transit.TripPattern;
import gnu.trove.iterator.TObjectIntIterator;
import gnu.trove.map.TIntIntMap;
import gnu.trove.map.TIntObjectMap;
import gnu.trove.map.TObjectIntMap;
import gnu.trove.map.hash.TIntIntHashMap;
import gnu.trove.map.hash.TIntObjectHashMap;
import gnu.trove.map.hash.TObjectIntHashMap;
import gnu.trove.set.TIntSet;
import gnu.trove.set.hash.TIntHashSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

/** An NYCFareDataCache contains fare data specific to NYC, for a specific transitlayer */
public final class NYCFareDataCache {
    private static final Logger LOG = LoggerFactory.getLogger(NYCFareDataCache.class);

    public final TIntObjectMap<LIRRStop> lirrStopForTransitLayerStop = new TIntObjectHashMap<>();
    public final TObjectIntMap<String> transitLayerStopForMnrStop = new TObjectIntHashMap<>();
    public final TIntSet peakLirrPatterns = new TIntHashSet();
    public final TIntSet allLirrPatterns = new TIntHashSet();
    public NYCPatternType[] patternTypeForPattern;
    /** St George and Tompkinsville stops where fare is paid on Staten Island Rwy */
    public final TIntSet statenIslandRwyFareStops = new TIntHashSet();

    /** Similar to SIR, Howard Beach and Jamaica stations charge a fare for entering or exiting JFK Airtrain */
    public final TIntSet airtrainJfkFareStops = new TIntHashSet();

    /** map from stop indices to (interned) fare areas for use in calculating free subway transfers */
    public final TIntObjectMap<String> fareAreaForStop = new TIntObjectHashMap<>();

    /** Metro-North peak fares, map from from stop -> to stop -> fare */
    public final TIntObjectMap<TIntIntMap> mnrPeakFares = new TIntObjectHashMap<>();

    /** Metro-North peak fares, map from from stop -> to stop -> fare */
    public final TIntObjectMap<TIntIntMap> mnrOffpeakFares = new TIntObjectHashMap<>();

    /** Since there are no free transfers betwen lines on Metro-North, keep track of which line
     * we're on.
     */
    public final TIntObjectMap<NYCInRoutingFareCalculator.MetroNorthLine> mnrLineForPattern = new TIntObjectHashMap<>();


    public NYCFareDataCache(TransitLayer transitLayer) {
        patternTypeForPattern = new NYCPatternType[transitLayer.tripPatterns.size()];

        for (int i = 0; i < transitLayer.stopIdForIndex.size(); i++) {
            // slow but only happens during initialization
            String prefixedStopId = transitLayer.stopIdForIndex.get(i);
            if (prefixedStopId != null) { // can be null if stop is added by scenario
                String stopId = prefixedStopId.split(":", 2)[1]; // get rid of feed id
                if (stopId.startsWith("lirr")) {
                    lirrStopForTransitLayerStop.put(i, LIRRStop.valueOf(stopId.toUpperCase(Locale.US)));
                } else if (NYCStaticFareData.subwayTransfers.containsKey(stopId)) {
                    fareAreaForStop.put(i, NYCStaticFareData.subwayTransfers.get(stopId));
                } else if (stopId.startsWith("mnr")) {
                    transitLayerStopForMnrStop.put(stopId.substring(4), i); // get rid of mnr_ prefix
                } else if (NYCStaticFareData.statenIslandRwyFareStops.contains(stopId)) {
                    statenIslandRwyFareStops.add(i);
                } else if (NYCStaticFareData.airtrainJfkFareStops.contains(stopId)) {
                    airtrainJfkFareStops.add(i);
                }
            } else {
                LOG.warn("Stop {} has no stop ID. If this is a stop from a modification, this is harmless; otherwise, you should look into it.", i);
            }
        }

        if (airtrainJfkFareStops.size() != 2) {
            throw new IllegalStateException("Did not find two AirTrain JFK fare stops! (data version mismatch?)");
        }

        // six because one for each platform and one parent at Tottenville and St George
        if (statenIslandRwyFareStops.size() != 6) {
            throw new IllegalStateException("Did not find six Staten Island Rwy fare stops! (data version mismatch?)");
        }

        for (int i = 0; i < transitLayer.tripPatterns.size(); i++) {
            TripPattern pat = transitLayer.tripPatterns.get(i);

            // routeId is feed-scoped (feedId:routeId) for routes in the baseline GTFS, and not feed scoped (just
            // a UUID routeId) for routes added by modification.
            String [] patternRouteId = pat.routeId.split(":", 2);
            String routeId = patternRouteId.length > 1 ? patternRouteId[1] : patternRouteId[0];

            int routeType = transitLayer.routes.get(pat.routeIndex).route_type;
            if (routeId.startsWith("lirr")) {
                allLirrPatterns.add(i);
                if (!pat.routeId.endsWith("offpeak")) {
                    peakLirrPatterns.add(i);
                    patternTypeForPattern[i] = NYCPatternType.LIRR_PEAK;
                } else {
                    patternTypeForPattern[i] = NYCPatternType.LIRR_OFFPEAK;
                }
            } else if (routeId.startsWith("mnr")) {
                if (routeId.endsWith("offpeak")) patternTypeForPattern[i] = NYCPatternType.METRO_NORTH_OFFPEAK;
                else patternTypeForPattern[i] = NYCPatternType.METRO_NORTH_PEAK;

                // figure out what line it's on
                String routeLongName = transitLayer.routes.get(pat.routeIndex).route_long_name;

                if (routeLongName.equals("Harlem")) mnrLineForPattern.put(i, NYCInRoutingFareCalculator.MetroNorthLine.HARLEM);
                else if (routeLongName.equals("Hudson")) mnrLineForPattern.put(i, NYCInRoutingFareCalculator.MetroNorthLine.HUDSON);
                // New Haven line has many branches
                else if (routeLongName.equals("New Haven") || routeLongName.equals("New Canaan") ||
                        routeLongName.equals("Waterbury") || routeLongName.equals("Danbury") ||
                        routeLongName.equals("MNR Shore Line East")) mnrLineForPattern.put(i, NYCInRoutingFareCalculator.MetroNorthLine.NEW_HAVEN);
                else throw new IllegalStateException("Unrecognized Metro-North route_long_name " + routeLongName);
            } else if (routeId.startsWith("bus")) {
                // Figure out if it's a local bus or an express bus
                String[] split = routeId.split("_");
                String rawRouteId = split[split.length - 1]; // the original GTFS route ID
                if (NYCStaticFareData.expressBusRoutes.contains(rawRouteId)) {
                    patternTypeForPattern[i] = NYCPatternType.METROCARD_EXPRESS_BUS;
                } else {
                    patternTypeForPattern[i] = NYCPatternType.METROCARD_LOCAL_BUS;
                }
            } else if (routeId.startsWith("nyct_subway")) {
                // Figure out if it's the Staten Island Railway
                if (routeId.equals("nyct_subway_SI")) patternTypeForPattern[i] = NYCPatternType.STATEN_ISLAND_RWY;
                else patternTypeForPattern[i] = NYCPatternType.METROCARD_SUBWAY;
            } else if (routeId.startsWith("si-ferry")) {
                patternTypeForPattern[i] = NYCPatternType.STATEN_ISLAND_FERRY;
            } else if (routeId.startsWith("ferry")) {
                // NYC Ferry
                if (routeType == 4) patternTypeForPattern[i] = NYCPatternType.NYC_FERRY; // boat
                else if (routeType == 3) patternTypeForPattern[i] = NYCPatternType.NYC_FERRY_BUS; // free shuttle bus
                else throw new IllegalArgumentException("unexpected route type in NYC Ferry feed");
            } else if (routeId.startsWith("westchester")) {
                String routeShortName = transitLayer.routes.get(pat.routeIndex).route_short_name;
                if (routeShortName.equals("BxM4C")) patternTypeForPattern[i] = NYCPatternType.WESTCHESTER_BXM4C;
                else patternTypeForPattern[i] = NYCPatternType.METROCARD_LOCAL_BUS; // same fare rules as MTA local bus
            } else if (routeId.startsWith("nice")) {
                patternTypeForPattern[i] = NYCPatternType.METROCARD_NICE;
            } else if (routeId.startsWith("suffolk")) {
                patternTypeForPattern[i] = NYCPatternType.SUFFOLK;
            } else if (routeId.startsWith("airtrain")) {
                patternTypeForPattern[i] = NYCPatternType.AIRTRAIN_JFK;
            }

            // HACK FOR SCENARIO APPLICATION
            // If mode is Gondola, assume RI Tram which has MetroCard Local Bus fares
            // If mode is Cable Car, LIRR Offpeak. If mode is funicular, LIRR Peak (pun intended)
            // Note that the conditions below can only be called if nothing matches above
            else if (routeType == 6) patternTypeForPattern[i] = NYCPatternType.METROCARD_LOCAL_BUS;
            else if (routeType == 5) {
                patternTypeForPattern[i] = NYCPatternType.LIRR_OFFPEAK;
                allLirrPatterns.add(i);
            }
            else if (routeType == 7) {
                patternTypeForPattern[i] = NYCPatternType.LIRR_PEAK;
                peakLirrPatterns.add(i);
                allLirrPatterns.add(i);
            }

            if (patternTypeForPattern[i] == null){
                throw new IllegalStateException("No pattern type assigned for pattern on route " + routeId);
            }
        }

        // construct MNR fare tables
        NYCStaticFareData.mnrPeakFares.forEach((fromStop, toStops) -> {
            int fromTransitLayerStop = transitLayerStopForMnrStop.get(fromStop);
            TIntIntMap toTransitLayerStops = new TIntIntHashMap();
            toStops.forEachEntry((toStop, fare) -> {
                        int toTransitLayerStop = transitLayerStopForMnrStop.get(toStop);
                        toTransitLayerStops.put(toTransitLayerStop, fare);
                        return true; // continue iteration
                    });
            mnrPeakFares.put(fromTransitLayerStop, toTransitLayerStops);
        });

        NYCStaticFareData.mnrOffpeakFares.forEach((fromStop, toStops) -> {
            int fromTransitLayerStop = transitLayerStopForMnrStop.get(fromStop);
            TIntIntMap toTransitLayerStops = new TIntIntHashMap();
            toStops.forEachEntry((toStop, fare) -> {
                int toTransitLayerStop = transitLayerStopForMnrStop.get(toStop);
                toTransitLayerStops.put(toTransitLayerStop, fare);
                return true; // continue iteration
            });
            mnrOffpeakFares.put(fromTransitLayerStop, toTransitLayerStops);
        });


        // print stats
        TObjectIntMap<NYCPatternType> hist = new TObjectIntHashMap<>();
        for (NYCPatternType type : NYCPatternType
                .values()) hist.put(type, 0);
        for (int i = 0; i < patternTypeForPattern.length; i++) {
            NYCPatternType type = patternTypeForPattern[i];
            if (type == null) {
                TripPattern pat = transitLayer.tripPatterns.get(i);
                throw new NullPointerException("Pattern type is null for pattern on route " + pat.routeId);
            } else {
                hist.increment(type);
            }
        }
        LOG.info("NYC fare pattern types:");
        for (TObjectIntIterator<NYCPatternType> it = hist.iterator(); it.hasNext();) {
            it.advance();
            LOG.info("  {}: {}", it.key(), it.value());
        }
    }

    public int getMetroNorthFare (int fromStop, int toStop, boolean peak) {
        TIntObjectMap<TIntIntMap> fares = (peak ? mnrPeakFares : mnrOffpeakFares);
        if (!fares.containsKey(fromStop) || !fares.get(fromStop).containsKey(toStop)) {
            throw new IllegalArgumentException("Could not find Metro-North fare!");
        }

        return fares.get(fromStop).get(toStop);
    }
}
