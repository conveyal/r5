package com.conveyal.gtfs.model;

import com.conveyal.gtfs.GTFSFeed;
import com.conveyal.gtfs.flex.FlexStopTime;
import com.google.common.base.Strings;
import org.mapdb.Fun;

import java.io.IOException;
import java.io.Serializable;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Set;

/// Represents one row of the GTFS stop_times table. Note that once created and saved in a feed,
/// StopTimes are by convention immutable because they are in a MapDB. We perform some referential
/// integrity checks without storing any references to other GTFS model objects. StopTime cannot
/// directly reference Trips or Stops because they would be transitively serialized into the MapDB.
public class StopTime extends Entity implements Cloneable, Serializable {
    private static final long serialVersionUID = -8883780047901081832L;

    public String trip_id;
    public int    arrival_time = INT_MISSING;
    public int    departure_time = INT_MISSING;
    public String stop_id;
    public int    stop_sequence;
    public String stop_headsign;
    public int    pickup_type;
    public int    drop_off_type;
    public double shape_dist_traveled;
    public int    timepoint = INT_MISSING;

    @Override
    public String getId() {
        return trip_id; // Concatenate with sequence number to make unique
    }

    @Override
    public Integer getSequenceNumber() {
        return stop_sequence; // Compound key of StopTime is (trip_id, stop_sequence)
    }

    public static class Loader extends Entity.Loader<StopTime> {
        private boolean tableHasFlex;

        public Loader(GTFSFeed feed) {
            super(feed, "stop_times");
        }

        @Override
        protected boolean isRequired() {
            return true;
        }

        @Override
        public void loadOneRow() throws IOException {
            boolean rowIsFlex = detectRowIsFlex();
            StopTime st = rowIsFlex ? loadFlexFields() : new StopTime();
            /// Load the fields present in both StopTime and FlexStopTime
            st.sourceFileLine = row;
            st.trip_id        = getStringField("trip_id", true);
            // TODO: arrival_time and departure time are not required, but if one is present the other should be
            // also, if this is the first or last stop, they are both required
            // also, if this is a flex stop
            st.arrival_time   = getTimeField("arrival_time", false);
            st.departure_time = getTimeField("departure_time", false);
            st.stop_id        = getStringField("stop_id", false);
            st.stop_sequence  = getIntField("stop_sequence", true, 0, Integer.MAX_VALUE);
            st.stop_headsign  = getStringField("stop_headsign", false);
            st.pickup_type    = getIntField("pickup_type", false, 0, 3); // TODO add ranges as parameters
            st.drop_off_type  = getIntField("drop_off_type", false, 0, 3);
            st.shape_dist_traveled = getDoubleField("shape_dist_traveled", false, 0D, Double.MAX_VALUE); // FIXME using both 0 and NaN for "missing", define DOUBLE_MISSING
            st.timepoint      = getIntField("timepoint", false, 0, 1, INT_MISSING);

            feed.stop_times.put(new Fun.Tuple2(st.trip_id, st.stop_sequence), st);
            getRefField("trip_id", true, feed.trips);
            if (rowIsFlex) {
                feed.flexTripIds.add(st.trip_id);
            } else {
                // Flex stop_times do not require a stop_id, but regular scheduled stop_times do.
                // Missing stop_id on non-flex would be caught here in referential integrity check.
                getRefField("stop_id", true, feed.stops);
            }
        }

        private static final Set<String> flexFields = Set.of("start_pickup_drop_off_window",
              "end_pickup_drop_off_window", "location_id", "location_group_id",
              "pickup_booking_rule_id", "drop_off_booking_rule_id");

        private boolean detectTableHasFlex () throws IOException {
            return Arrays.stream(reader.getHeaders()).anyMatch(h -> flexFields.contains(h));
        }

        private boolean detectRowIsFlex () throws IOException {
            // Memoize slow check for presence of column names, then short-circuit slow check
            // for field presence on rows. Row number is one-based and includes the header row.
            if (row == 2) tableHasFlex = detectTableHasFlex();
            if (tableHasFlex) {
                for (String header : flexFields) {
                    if (!Strings.isNullOrEmpty(reader.get(header))) return true;
                }
            }
            return false;
        }

        /// Load the fields present only in FlexStopTime but not in the base StopTime
        private FlexStopTime loadFlexFields () throws IOException {
            FlexStopTime fst = new FlexStopTime();
            fst.start_pickup_drop_off_window = getTimeField("start_pickup_drop_off_window", false);
            fst.end_pickup_drop_off_window = getTimeField("end_pickup_drop_off_window", false);
            fst.location_id = getStringField("location_id", false);
            fst.location_group_id = getStringField("location_group_id", false);
            fst.pickup_booking_rule_id = getStringField("pickup_booking_rule_id", false);
            fst.drop_off_booking_rule_id = getStringField("drop_off_booking_rule_id", false);
            return fst;
        }
    }

    /// Note that saving StopTime data with this writer will cause FlexStopTimes to lose their
    /// extra fields. Writing flex data is not currently supported.
    public static class Writer extends Entity.Writer<StopTime> {
        public Writer (GTFSFeed feed) {
            super(feed, "stop_times");
        }

        @Override
        protected void writeHeaders() throws IOException {
            writer.writeRecord(new String[] {"trip_id", "arrival_time", "departure_time", "stop_id", "stop_sequence", "stop_headsign",
                    "pickup_type", "drop_off_type", "shape_dist_traveled", "timepoint"});
        }

        @Override
        protected void writeOneRow(StopTime st) throws IOException {
            writeStringField(st.trip_id);
            writeTimeField(st.arrival_time);
            writeTimeField(st.departure_time);
            writeStringField(st.stop_id);
            writeIntField(st.stop_sequence);
            writeStringField(st.stop_headsign);
            writeIntField(st.pickup_type);
            writeIntField(st.drop_off_type);
            writeDoubleField(st.shape_dist_traveled);
            writeIntField(st.timepoint);
            endRecord();
        }

        @Override
        protected Iterator<StopTime> iterator() {
            return feed.stop_times.values().iterator();
        }
    }

    @Override
    public StopTime clone () {
        try {
            return (StopTime) super.clone();
        } catch (CloneNotSupportedException e) {
            throw new RuntimeException(e);
        }
    }
}
