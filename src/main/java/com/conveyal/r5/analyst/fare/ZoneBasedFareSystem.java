package com.conveyal.r5.analyst.fare;

import java.util.Map;

/*
Fares specified only by origin_id and destination_id.
 */

public class ZoneBasedFareSystem {
    public Map<Pair, Integer> fareByZonePair;

    public class Pair {
        String origin_id; // zone_id of stop at start of journey stage
        String destination_id; // zone_id of stop at end of journey stage

        public Pair(String origin_id, String destination_id){
            this.origin_id = origin_id;
            this.destination_id = destination_id;
        }
    }

    public void addZonePair(String origin_id, String destination_id, int price) {
        Pair pair = new Pair(origin_id, destination_id);
        fareByZonePair.put(pair, price);
    }

    public int getFare(String origin_id, String destination_id){
        Pair pair = new Pair(origin_id, destination_id);
        return fareByZonePair.get(pair);
    }

}
