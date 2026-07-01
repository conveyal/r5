package com.conveyal.gtfs.flex;

import com.conveyal.gtfs.model.StopTime;

/// Although on-demand ("flex") services are expressed as extended GTFS stop_times rows, they are
/// quite different from scheduled transit rows, especially where polygonal zones ("locations") are
/// used. One set of fields remains completely unused, while another is only used for flex services.
/// Therefore we load these special case rows into a different data structure.
///
/// Some kinds of flex trips can mix stops and zones. This probably involves multiple types of stop
/// time objects, as they have mostly mutually exclusive fields. We are not handling that for now.
///
/// This class cannot hold Java object references to GTFS entity objects like trips or locations.
/// It must reference them by ID, otherwise they would be transitively serialized into the MapDB.
///
/// Limitations: these classes cannot be written back out to GTFS format.
/// Running a GTFS Flex feed through a load-transform-save cycle will drop all flex services.
public class FlexStopTime extends StopTime {
    // This extends StopTime, so only additional fields are covered here. This is somewhat
    // inefficient as many fields are empty, but flex trips tend to have very few stop_times.
    public int    start_pickup_drop_off_window = INT_MISSING;
    public int    end_pickup_drop_off_window = INT_MISSING;
    public String location_id;
    public String location_group_id;
    public String pickup_booking_rule_id;
    public String drop_off_booking_rule_id;
}
