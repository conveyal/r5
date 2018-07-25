package com.conveyal.r5.analyst.fare;

/*
Fares specified by route_id (and optionally origin_id/destination_id).
 */

import com.conveyal.gtfs.model.Fare;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public class RouteBasedFareSystem {
    // Map from route_id values to Fare objects from gtfs-lib.  Per GTFS spec, fare_rules.txt can have multiple rows
    // with the same route (e.g. in a mixed route/zone fare system).

    public Map<FareKey, Fare> byRouteKey = new HashMap<>();
    public Map<String, Fare> byId = new HashMap<>();

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
}
