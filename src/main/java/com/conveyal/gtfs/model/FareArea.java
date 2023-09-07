package com.conveyal.gtfs.model;

import com.conveyal.gtfs.GTFSFeed;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;

/** A FareArea represents a group of stops in the GTFS Fares V2 specification */
public class FareArea extends Entity {
    private static final long serialVersionUID = 1L;

    public String fare_area_id;
    public String fare_area_name;
    public String ticketing_fare_area_id;
    public Collection<FareAreaMember> members = new ArrayList<>();

    public static class Loader extends Entity.Loader<FareArea> {
        private Map<String, FareArea> fareAreas;

        public Loader (GTFSFeed feed, Map<String, FareArea> fareAreas) {
            super(feed, "fare_areas");
            this.fareAreas = fareAreas;
        }

        @Override
        protected boolean isRequired() {
            return false;
        }

        @Override
        protected void loadOneRow() throws IOException {
            // Fare areas are composed of members that refer to specific stops or trip/stop combos
            FareAreaMember member = new FareAreaMember();
            member.stop_id = getStringField("stop_id", false);
            member.trip_id = getStringField("trip_id", false);
            member.stop_sequence = getIntField("stop_sequence", false, 0, Integer.MAX_VALUE, INT_MISSING);
            member.sourceFileLine = row + 1;

            String fareAreaId = getStringField("fare_area_id", true);

            FareArea fareArea;
            if (fareAreas.containsKey(fareAreaId)) {
                fareArea = fareAreas.get(fareAreaId);
                // TODO make sure that fare_area_name, etc all match
            } else {
                fareArea = new FareArea();
                fareArea.fare_area_id = fareAreaId;
                fareArea.fare_area_name = getStringField("fare_area_name", false);
                fareArea.ticketing_fare_area_id = getStringField("ticketing_fare_area_id", false);
                fareAreas.put(fareAreaId, fareArea);
            }
            fareArea.members.add(member);
        }
    }

    /** What are the members of this FareArea? */
    public static class FareAreaMember implements Serializable {
        private static final long serialVersionUID = 1L;
        public String stop_id;
        public String trip_id;
        public int stop_sequence;
        public int sourceFileLine;
    }
}
