package com.conveyal.r5.analyst.fare;

import com.conveyal.gtfs.model.Fare;
import com.conveyal.gtfs.model.FareRule;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Multiple Fare Rules, with convenience methods for looking up by fare_id, route_id, origin zone, and destination zone.
 *
 * Used with InRoutingFareCalculator (and subclasses) for Pareto searches including fares in McRaptorSuboptimalPathProfileRouter.
 */
public class RouteBasedFareRules {
    // Map from route_id values to Fare objects from gtfs-lib.  Per GTFS spec, fare_rules.txt can have multiple rows
    // with the same route (e.g. in a mixed route/zone fare system).

    public Map<FareKey, Fare> byRouteKey = new HashMap<>();
    public Map<String, Fare> byId = new HashMap<>();
    public String defaultFare;

    public static class FareKey {
        String routeId;
        String origin_zone_id;
        String destination_zone_id;

        // cache hash code for performance
        private int hashCode;

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            FareKey fareKey = (FareKey) o;
            return Objects.equals(routeId, fareKey.routeId) &&
                    Objects.equals(origin_zone_id, fareKey.origin_zone_id) &&
                    Objects.equals(destination_zone_id, fareKey.destination_zone_id);
        }

        @Override
        public int hashCode() {
            return hashCode;
        }

        public void computeHashCode () {
            hashCode = 0;
            if (routeId != null) hashCode += routeId.hashCode();
            else {
                if (origin_zone_id != null) hashCode += origin_zone_id.hashCode() * 31;
                if (destination_zone_id != null) hashCode += destination_zone_id.hashCode() * 131;
            }
        }

        public FareKey(String route, String origin, String destination){
            this.routeId = route;
            this.origin_zone_id = origin;
            this.destination_zone_id = destination;
            this.computeHashCode();
        }
    }

    public void addFareRules(Fare fare){
        for (FareRule fareRule : fare.fare_rules){
            String route = fareRule.route_id;
            String origin = fareRule.origin_id;
            String destination = fareRule.destination_id;
            FareKey fareKey = new FareKey(route, origin, destination);
            byRouteKey.put(fareKey, fare);
        }

        byId.put(fare.fare_id, fare);
    }

    /**
    If the given route_id, and optionally board stop zone_id and alight stop zone_id, have been associated with a fare
    (e.g. in GTFS fare_rules.txt), return that fare. Otherwise, return the default fare.  In many systems, the
    majority of routes (e.g. buses) will be associated with one fare; this approach avoids having to enumerate all
    the routes that have a default fare.
     @param route route_id from GTFS
     @param start zone_id of boarding stop from GTFS
     @param end zone_id of alighting stop from GTFS
     @return the best match from this route-based fare system, or the default fare if no match is found.
     **/

    public Fare getFareOrDefault(String route, String start, String end){
        // Order is important here.  We want to return the most explicit match.  For example, fare_rules.txt might
        // have one row for Route 1, and another row for Route 1 at Stop A.  We'd want to return the latter.
        String[] originOrWildcard = {start, null};
        String[] destinationOrWildcard = {end, null};
        for (String origin : originOrWildcard){
            for (String destination : destinationOrWildcard){
                FareKey fareKey = new FareKey(route, origin, destination);
                if (byRouteKey.containsKey(fareKey)) {
                    return byRouteKey.get(fareKey);
                }
            }
        }
        return byId.get(defaultFare);
    }

}
