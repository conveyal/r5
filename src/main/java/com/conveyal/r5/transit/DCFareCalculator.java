package com.conveyal.r5.transit;

import com.conveyal.gtfs.model.Route;
import com.conveyal.gtfs.model.Stop;
import com.conveyal.r5.transit.fare.FareTable;

import java.awt.geom.Rectangle2D;

/**
 * Created by mabu on 7.3.2016.
 */
public class DCFareCalculator {

    private static final FareTable METRORAIL = new FareTable("fares/dc/metrorail.csv");
    private static final FareTable MARC = new FareTable("fares/dc/marc.csv");
    private static final FareTable VRE = new FareTable("fares/dc/vre.csv");

    private static final String[] metroExpress = { "J7", "J9", "P17", "P19", "W13", "W19", "11Y", "17A", "17B", "17G",
        "17H", "17K", "17L", "17M", "18E", "18G", "18H", "18P", "29E", "29G", "29H", "29X" };

    // geographic fare areas for MTA (Maryland) commuter buses
    private static final FareArea MTA_DC = new FareArea(-77.066139,38.868166,-76.986868,38.926);
    private static final FareArea MTA_SILVER_SPRING = new FareArea(-76.943069,38.838617,-76.916848,38.855997);
    private static final FareArea MTA_SUITLAND = new FareArea(-77.04039,38.985653,-77.02017,39.002778);
    private static final FareArea MTA_KENT_ISLAND = new FareArea(-76.360503,38.939413,-76.170122,39.012155);
    private static final FareArea MTA_DAVIDSONVILLE = new FareArea(-76.669367,38.952661,-76.637353,38.970023);
    private static final FareArea MTA_ANNAPOLIS = new FareArea(-76.574267,38.960267,-76.469125,39.001783);
    private static final FareArea MTA_OWINGS_NORTH_BEACH = new FareArea(-76.623115,38.687874,-76.519002,38.732147);
    private static final FareArea MTA_PINDELL = new FareArea(-76.772156,38.754121,-76.639891,38.830921);
    private static final FareArea MTA_CHARLOTTE_HALL_WALDORF = new FareArea(-76.97628,38.435241,-76.71225,38.656712);
    private static final FareArea MTA_CALIFORNIA = new FareArea(-76.571502,38.273603,-76.483254,38.3446);
    private static final FareArea MTA_DUNKIRK = new FareArea(-76.677069, 38.692187, -76.635025, 38.742091);
    private static final FareArea MTA_SUNDERLAND = new FareArea(-76.627974, 38.530956, -76.564987, 38.678062);
    private static final FareArea MTA_ST_LEONARD = new FareArea(-76.527123, 38.458123, -76.48802, 38.495911);

    private static RideType classify (Route route) {
        // NOTE the agencyId string of the route's agencyAndId is not the same as the one given by route.getAgency.
        // The former is the same for all routes in the feed. The latter is the true agency of the feed.

        String agency = route.agency.agency_id;
        String agency_url = route.agency.agency_url.toString(); // this is used in single-agency feeds so it should work
        String short_name = route.route_short_name;
        String long_name = route.route_long_name;
        if ("MET".equals(agency)) {
            if (route.route_type == 1) return RideType.METRO_RAIL;
            if ("5A".equals(short_name) || "B30".equals(short_name)) return RideType.METRO_BUS_AIRPORT;
            for (String sn : metroExpress) if (sn.equals(short_name)) return RideType.METRO_BUS_EXPRESS;
            return RideType.METRO_BUS_LOCAL;
        } else if ("DC".equals(agency)) {
            return RideType.DC_CIRCULATOR_BUS;
        } else if ("MCRO".equals(agency)) {
            if (short_name.equals("70")) return RideType.MCRO_BUS_EXPRESS;
            else return RideType.MCRO_BUS_LOCAL;
        } else if (agency_url != null) {
            if (agency_url.contains("fairfaxconnector.com")) {
                return RideType.FAIRFAX_CONNECTOR_BUS;
            }
            if (agency_url.contains("prtctransit.org")) {
                return RideType.PRTC_BUS;
            }
            if (agency_url.contains("arlingtontransit.com")) {
                return RideType.ART_BUS;
            }
            if (agency_url.contains("vre.org")) {
                return RideType.VRE_RAIL;
            }
            if (agency_url.contains("mtamaryland.com")) {
                if (route.route_type == 2) return RideType.MARC_RAIL;
                int shortName;
                try {
                    shortName = Integer.parseInt(route.route_short_name);
                }
                catch(NumberFormatException ex) {
                    // assume a local bus if route number cannot be parsed
                    return RideType.MTA_BUS_LOCAL;
                }
                if(shortName < 100) { // local routes are 0 - 99
                    return RideType.MTA_BUS_LOCAL;
                }
                else if(shortName < 200) { // express routes are 100 - 199
                    return RideType.MTA_BUS_EXPRESS;
                }
                // commuter routes are 200+
                return RideType.MTA_BUS_COMMUTER;
            }
        }
        return null;
    }

    static class FareArea extends Rectangle2D.Double {

        public FareArea(double min_lon, double min_lat, double max_lon, double max_lat) {
            super(min_lon, min_lat, max_lon - min_lon, max_lat - min_lat);
        }

        public boolean containsStop(Stop stop) {
            return super.contains(stop.stop_lon, stop.stop_lat);
        }
    }
    public static class Fare {

        public RideType type;
        public double low;
        public double peak;
        public double senior;
        public boolean transferReduction;

        public Fare (Fare other) {
            this.accumulate(other);
        }

        public Fare (double base) {
            low = peak = senior = base;
        }

        public Fare (double low, double peak, double senior) {
            this.low = low;
            this.peak = peak;
            this.senior = senior;
        }

        public void accumulate (Fare other) {
            if (other != null) {
                low    += other.low;
                peak   += other.peak;
                senior += other.senior;
            }
        }

        public void discount(double amount) {
            low    -= amount;
            peak   -= amount;
            senior -= amount;
            transferReduction = true;
        }

    }

    enum RideType {
        METRO_RAIL,
        METRO_BUS_LOCAL,
        METRO_BUS_EXPRESS,
        METRO_BUS_AIRPORT,
        DC_CIRCULATOR_BUS,
        ART_BUS,
        DASH_BUS,
        MARC_RAIL,
        MTA_BUS_LOCAL,
        MTA_BUS_EXPRESS,
        MTA_BUS_COMMUTER,
        VRE_RAIL,
        MCRO_BUS_LOCAL,
        MCRO_BUS_EXPRESS,
        FAIRFAX_CONNECTOR_BUS,
        PRTC_BUS
    }
}
