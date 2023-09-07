package com.conveyal.gtfs.model;

import com.conveyal.gtfs.GTFSFeed;

import java.io.IOException;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** GTFS-Fares V2 FareNetwork. Not represented exactly in GTFS, but a single entry for each FareNetwork */
public class FareNetwork extends Entity {
    public static final long serialVersionUID = 1L;

    public String fare_network_id;
    public int as_route;
    public Set<String> route_ids = new HashSet<>();

    public static class Loader extends Entity.Loader<FareNetwork> {
        private Map<String, FareNetwork> fareNetworks;

        public Loader (GTFSFeed feed, Map<String, FareNetwork> fareNetworks) {
            super(feed, "fare_networks");
            this.fareNetworks = fareNetworks;
        }

        @Override
        protected boolean isRequired() {
            return false;
        }

        @Override
        protected void loadOneRow() throws IOException {
            String fareNetworkId = getStringField("fare_network_id", true);

            FareNetwork fareNetwork;
            if (fareNetworks.containsKey(fareNetworkId)) {
                fareNetwork = fareNetworks.get(fareNetworkId);
                // TODO confirm as_route is consistent
            } else {
                fareNetwork = new FareNetwork();
                fareNetwork.fare_network_id = fareNetworkId;
                fareNetwork.as_route = getIntField("as_route", false, 0, 1, 0);
                fareNetworks.put(fareNetworkId, fareNetwork);
            }

            fareNetwork.route_ids.add(getStringField("route_id", true));
        }
    }
}
