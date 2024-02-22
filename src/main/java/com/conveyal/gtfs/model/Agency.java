package com.conveyal.gtfs.model;

import com.conveyal.gtfs.GTFSFeed;

import java.io.IOException;
import java.net.URL;
import java.util.Iterator;

public class Agency extends Entity {

    private static final long serialVersionUID = -2825890165823575940L;
    public String agency_id;
    public String agency_name;
    public URL    agency_url;
    public String agency_timezone;
    public String agency_lang;
    public String agency_email;
    public String agency_phone;
    public URL    agency_fare_url;
    public URL    agency_branding_url;
    public String feed_id;

    @Override
    public String getId() {
        return agency_id;
    }

    public static class Loader extends Entity.Loader<Agency> {

        public Loader(GTFSFeed feed) {
            super(feed, "agency");
        }

        @Override
        protected boolean isRequired() {
            return true;
        }

        @Override
        public void loadOneRow() throws IOException {
            Agency a = new Agency();
            a.sourceFileLine = row;
            a.agency_id    = getStringField("agency_id", false); // can only be absent if there is a single agency -- requires a special validator.
            a.agency_name  = getStringField("agency_name", true);
            a.agency_url   = getUrlField("agency_url", true);
            a.agency_lang  = getStringField("agency_lang", false);
            a.agency_email = getStringField("agency_email", false);
            a.agency_phone = getStringField("agency_phone", false);
            a.agency_timezone = getStringField("agency_timezone", true);
            a.agency_fare_url = getUrlField("agency_fare_url", false);
            a.agency_branding_url = getUrlField("agency_branding_url", false);
            a.feed_id = feed.feedId;
            // Kludge because mapdb does not support null keys
            if (a.agency_id == null) {
                a.agency_id = "NONE";
            }
            insertCheckingDuplicateKey(feed.agency, a, "agency_id");
        }

    }

    public static class Writer extends Entity.Writer<Agency> {
        public Writer(GTFSFeed feed) {
            super(feed, "agency");
        }

        @Override
        public void writeHeaders() throws IOException {
            writer.writeRecord(new String[] {"agency_id", "agency_name", "agency_url", "agency_lang",
                    "agency_phone", "agency_email", "agency_timezone", "agency_fare_url", "agency_branding_url"});
        }

        @Override
        public void writeOneRow(Agency a) throws IOException {
            writeStringField(a.agency_id);
            writeStringField(a.agency_name);
            writeUrlField(a.agency_url);
            writeStringField(a.agency_lang);
            writeStringField(a.agency_phone);
            writeStringField(a.agency_email);
            writeStringField(a.agency_timezone);
            writeUrlField(a.agency_fare_url);
            writeUrlField(a.agency_branding_url);
            endRecord();
        }

        @Override
        public Iterator<Agency> iterator() {
            return this.feed.agency.values().iterator();
        }
    }

}
