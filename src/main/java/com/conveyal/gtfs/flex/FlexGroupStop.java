package com.conveyal.gtfs.flex;

import com.conveyal.gtfs.GTFSFeed;
import com.conveyal.gtfs.model.Entity;

import java.io.IOException;
import java.io.Serializable;
import java.util.Map;

/// Used for loading the GTFS Flex table that associates stops and locations with location_groups.
/// These row objects are never actually instantiated.
/// They are immediately joined to the relevant location group.
public abstract class FlexGroupStop extends Entity implements Serializable {
    public static class Loader extends Entity.Loader<FlexGroupStop> {
        private final Map<String, FlexGroup> flexGroups;

        public Loader (GTFSFeed feed, Map<String, FlexGroup> flexGroups) {
            super(feed, "location_group_stops");
            this.flexGroups = flexGroups;
        }

        @Override
        protected boolean isRequired () {
            return false;
        }

        @Override
        protected void loadOneRow () throws IOException {
            String groupId = getStringField("location_group_id", true);
            String stopId = getStringField("stop_id", true);
            FlexGroup group = flexGroups.get(groupId);
            if (feed.stops.containsKey(stopId)) {
                group.stop_ids.add(stopId);
            } else if (feed.locations.containsKey(stopId)) {
                referenceError("stop_id", "Location group must only reference stops, not locations.");
            } else {
                referenceError("stop_id", "Location group must reference elements from the stops table.");
            }
            // Current FlexGroup object is an in-memory map so does not need to be re-persisted.
        }
    }

}
