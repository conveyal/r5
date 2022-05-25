package com.conveyal.gtfs.model;

import com.conveyal.gtfs.GTFSFeed;

import java.io.IOException;
import java.net.URL;
import java.util.Iterator;

public class Stop extends Entity {

    private static final long serialVersionUID = 464065335273514677L;
    public String stop_id;
    public String stop_code;
    public String stop_name;
    public String stop_desc;
    public double stop_lat;
    public double stop_lon;
    public String zone_id;
    public URL    stop_url;
    public int    location_type;
    public String parent_station;
    public String stop_timezone;
    // TODO should be int
    public String wheelchair_boarding;
    public String feed_id;

    @Override
    public String getId () {
        return stop_id;
    }

    public static class Loader extends Entity.Loader<Stop> {

        public Loader(GTFSFeed feed) {
            super(feed, "stops");
        }

        @Override
        protected boolean isRequired() {
            return true;
        }

        @Override
        public void loadOneRow() throws IOException {
            Stop s = new Stop();
            s.sourceFileLine = row + 1; // offset line number by 1 to account for 0-based row index
            s.stop_id   = getStringField("stop_id", true);
            s.stop_code = getStringField("stop_code", false);
            s.stop_name = getStringField("stop_name", true);
            s.stop_desc = getStringField("stop_desc", false);
            s.zone_id   = getStringField("zone_id", false);
            s.stop_url  = getUrlField("stop_url", false);
            s.location_type  = getIntField("location_type", false, 0, 4);
            // 0...2 are stop, station, and entrance which must have lat and lon. Other nodes do not need coordinates.
            boolean coord_required = s.location_type <= 2;
            s.stop_lat  = getDoubleField("stop_lat", coord_required, -90D, 90D);
            s.stop_lon  = getDoubleField("stop_lon", coord_required, -180D, 180D);
            // Required for entrances, generic nodes, and boarding areas. Optional for stops, forbidden for stations.
            boolean parent_station_required = s.location_type >= 2;
            s.parent_station = getStringField("parent_station", parent_station_required);
            s.stop_timezone  = getStringField("stop_timezone", false);
            s.wheelchair_boarding = getStringField("wheelchair_boarding", false);
            s.feed_id = feed.feedId;
            /* TODO check ref integrity later, this table self-references via parent_station */
            feed.stops.put(s.stop_id, s);
        }

    }

    public static class Writer extends Entity.Writer<Stop> {
        public Writer (GTFSFeed feed) {
            super(feed, "stops");
        }

        @Override
        public void writeHeaders() throws IOException {
            writer.writeRecord(new String[] {"stop_id", "stop_code", "stop_name", "stop_desc", "stop_lat", "stop_lon", "zone_id",					
                    "stop_url", "location_type", "parent_station", "stop_timezone", "wheelchair_boarding"});
        }

        @Override
        public void writeOneRow(Stop s) throws IOException {
            writeStringField(s.stop_id);
            writeStringField(s.stop_code);
            writeStringField(s.stop_name);
            writeStringField(s.stop_desc);
            writeDoubleField(s.stop_lat);
            writeDoubleField(s.stop_lon);
            writeStringField(s.zone_id);
            writeUrlField(s.stop_url);
            writeIntField(s.location_type);
            writeStringField(s.parent_station);
            writeStringField(s.stop_timezone);
            writeStringField(s.wheelchair_boarding);
            endRecord();
        }

        @Override
        public Iterator<Stop> iterator() {
            return feed.stops.values().iterator();
        }   	
    }
}
