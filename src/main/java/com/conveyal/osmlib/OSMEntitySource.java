package com.conveyal.osmlib;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;

/**
 * An interface for classes that read in OSM entities from somewhere and pipe them into an OSMEntitySink.
 * The flow of entities is push-oriented (the source calls write on the sink for every entity it can produce).
 * Entities should always be produced in the order nodes, ways, relations.
 */
public interface OSMEntitySource {

    /** Read the OSM entities from this source and pump them through to the sink. */
    public abstract void copyTo (OSMEntitySink sink) throws IOException;

    public static OSMEntitySource forUrl (String urlString) {
        try {
            URL url = new URL(urlString);
            InputStream inputStream = url.openStream();
            return forStream(urlString, inputStream);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static OSMEntitySource forFile (String path) {
        try {
            InputStream inputStream = new FileInputStream(path);
            return forStream(path, inputStream);
        } catch (FileNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    public static OSMEntitySource forStream (String name, InputStream inputStream) {
        if (name.endsWith(".pbf")) {
            return new PBFInput(inputStream);
        } else if (name.endsWith(".vex")) {
            return new VexInput(inputStream);
        } else {
            return null;
        }
    }

}
