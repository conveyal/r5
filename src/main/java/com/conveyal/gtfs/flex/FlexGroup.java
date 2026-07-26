package com.conveyal.gtfs.flex;


import com.conveyal.gtfs.GTFSFeed;
import com.conveyal.gtfs.model.Entity;
import com.conveyal.gtfs.model.StopTime;
import org.mapdb.Fun;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/// Combines information from the GTFS Flex location_groups and location_group_stops tables.
/// This class does not faithfully replicate the table structure of raw GTFS. Like our Services or
/// Shapes, they pre-group many rows together to compact the data and make it more readily usable.
/// Note that despite the name being location_groups, these groups can apparently only contain
/// references to pointlike stop entities from the GTFS stops table, not to polygonal locations
/// defined in the locations GeoJSON. The location_groups reference documentation says that this
/// table "assigns stops from stops.txt to location groups." The reference material on the
/// location_groups.stop_id field says this field is a "foreign ID referencing stops.stop_id".
/// A review of discussion on pull requests seems to indicate that location_groups were originally
/// intended to reference a mix of polygonal locations and pointlike stops, but late in the process
/// this was narrowed to only allow stops, while keeping the name that only references locations.
/// Although stops.stop_id, locations.id and location_groups.location_group_id all share a single
/// namespace, there are apparently no fields that can reference a mix of these entity types. The
/// discussion seems to indicate that location_groups cannot be nested and cannot include locations.
public class FlexGroup extends Entity implements Cloneable, Serializable {
    /// Increment to the current ISO date when any serialization-breaking changes are made.
    public static final long serialVersionUID = 20260515L;

    /// Unique machine-readable identifier, sharing a namespace with locations.id and stops.stop_id.
    public String location_group_id;

    /// Human-readable name of this group.
    public String location_group_name;

    /// IDs of traditional pointlike stops from the GTFS stops table, not polygons or groups.
    /// Despite their name, location_groups cannot reference locations, only stops.
    public List<String> stop_ids;

    @Override
    public String getId () {
        return location_group_id;
    }

    public static class Loader extends Entity.Loader<FlexGroup> {
        private final Map<String, FlexGroup> flexGroups;

        public Loader (GTFSFeed feed, Map<String, FlexGroup> flexGroups) {
            super(feed, "location_groups");
            this.flexGroups = flexGroups;
        }

        @Override
        protected boolean isRequired () {
            return false;
        }

        @Override
        protected void loadOneRow () throws IOException {
            FlexGroup group = new FlexGroup();
            group.location_group_id = getStringField("location_group_id", true);
            group.location_group_name = getStringField("location_group_name", false);
            group.stop_ids = new ArrayList<>();
            flexGroups.put(group.location_group_id, group);
        }
    }
}
