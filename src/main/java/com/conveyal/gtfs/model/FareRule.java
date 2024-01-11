package com.conveyal.gtfs.model;

import com.conveyal.gtfs.GTFSFeed;
import com.conveyal.gtfs.error.ReferentialIntegrityError;

import java.io.IOException;
import java.util.Iterator;
import java.util.Map;

public class FareRule extends Entity {

    private static final long serialVersionUID = 3209660216692732272L;
    public String fare_id;
    public String route_id;
    public String origin_id;
    public String destination_id;
    public String contains_id;

    public static class Loader extends Entity.Loader<FareRule> {

        private final Map<String, Fare> fares;

        public Loader(GTFSFeed feed, Map<String, Fare> fares) {
            super(feed, "fare_rules");
            this.fares = fares;
        }

        @Override
        protected boolean isRequired() {
            return false;
        }

        @Override
        public void loadOneRow() throws IOException {

            /* Calendars and Fares are special: they are stored as joined tables rather than simple maps. */
            String fareId = getStringField("fare_id", true);

            /* Referential integrity check for fare id */
            if (!fares.containsKey(fareId)) {
                this.feed.errors.add(new ReferentialIntegrityError(tableName, row, "fare_id", fareId));
            }

            Fare fare = fares.computeIfAbsent(fareId, Fare::new);
            FareRule fr = new FareRule();
            fr.sourceFileLine = row;
            fr.fare_id = fare.fare_id;
            fr.route_id = getStringField("route_id", false);
            fr.origin_id = getStringField("origin_id", false);
            fr.destination_id = getStringField("destination_id", false);
            fr.contains_id = getStringField("contains_id", false);
            fare.fare_rules.add(fr);

        }

    }

    public static class Writer extends Entity.Writer<FareRule> {
        public Writer(GTFSFeed feed) {
            super(feed, "fare_rules");
        }

        @Override
        public void writeHeaders() throws IOException {
            writer.writeRecord(new String[] {"fare_id", "route_id", "origin_id", "destination_id",
                    "contains_id"});
        }

        @Override
        public void writeOneRow(FareRule fr) throws IOException {
            writeStringField(fr.fare_id);
            writeStringField(fr.route_id);
            writeStringField(fr.origin_id);
            writeStringField(fr.destination_id);
            writeStringField(fr.contains_id);
            endRecord();
        }

        @Override
        public Iterator<FareRule> iterator() {
            return feed.fares.values().stream()
                    .map(f -> f.fare_rules)
                    .flatMap(fr -> fr.stream())
                    .iterator();
        }
    }

}
