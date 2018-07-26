package com.conveyal.r5.analyst.fare;

import com.conveyal.gtfs.model.Fare;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 Fare objects (joins of GTFS fare_rules and fare_attributes) specified by route_id (and optionally
 origin_id/destination_id).
 **/
public class RouteBasedFareSystem {
    // Map from route_id values to Fare objects from gtfs-lib.  Per GTFS spec, fare_rules.txt can have multiple rows
    // with the same route (e.g. in a mixed route/zone fare system).

    public Map<FareKey, Fare> byRouteKey = new HashMap<>();
    public Map<String, Fare> byId = new HashMap<>();
    public String defaultFare;

    public static class FareKey {
        String routeId;
        String origin_zone_id;
        String destination_zone_id;

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

            return Objects.hash(routeId, origin_zone_id, destination_zone_id);
        }

        public FareKey(String route, String origin, String destination){
            this.routeId = route;
            this.origin_zone_id = origin;
            this.destination_zone_id = destination;
        }
    }

    public void addFare(String route, String origin, String destination, Fare fare){
        FareKey fareKey = new FareKey(route, origin, destination);
        byRouteKey.put(fareKey, fare);
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
        String[] originOrWildcard = {start, ""};
        String[] destinationOrWildcard = {end, ""};
        for (String origin : originOrWildcard){
            for (String destination :destinationOrWildcard){
                FareKey fareKey = new FareKey(route, origin, destination);
                if (byRouteKey.containsKey(fareKey)) {
                    return byRouteKey.get(fareKey);
                }
            }
        }
        return byId.get(defaultFare);
    }

}
