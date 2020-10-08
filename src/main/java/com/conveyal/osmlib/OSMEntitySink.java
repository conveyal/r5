package com.conveyal.osmlib;

import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Created by abyrd on 2015-05-04
 *
 * TODO intersection finder, indexing sinks (or include those optionally in the OSM storage class itself.
 * TODO tag filter sink
 */
public interface OSMEntitySink {

    public void writeBegin() throws IOException;

    public void setReplicationTimestamp(long secondsSinceEpoch); // Needs to be called before any entities are written

    public void writeNode(long id, Node node) throws IOException; // TODO rename id parameters to nodeId, wayId, relationId throughout

    public void writeWay(long id, Way way) throws IOException;

    public void writeRelation(long id, Relation relation) throws IOException;

    public void writeEnd() throws IOException;

    public static OSMEntitySink forFile (String path) {
        try {
            OutputStream outputStream = new FileOutputStream(path);
            return forStream(path, outputStream);
        } catch (FileNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    public static OSMEntitySink forStream (String name, OutputStream outputStream) {
        if (name.endsWith(".pbf")) {
            return new PBFOutput(outputStream);
        } else if (name.endsWith(".vex")) {
            return new VexOutput(outputStream);
        } else if (name.endsWith(".txt")) {
            return new TextOutput(outputStream);
        } else {
            return null;
        }
    }

}
