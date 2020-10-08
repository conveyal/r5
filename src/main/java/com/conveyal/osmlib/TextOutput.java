package com.conveyal.osmlib;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.Locale;

/**
 * Write OSM out to a simple human-readable text format.
 * This seems to be very slow because it's using a printStream and format().
 */
public class TextOutput implements OSMEntitySink {

    OutputStream outputStream;
    PrintStream printStream;

    public boolean inlineNodes = false; // TODO will require a database connection

    public TextOutput(OutputStream outputStream) {
        this.outputStream = outputStream;
        this.printStream = new PrintStream(outputStream);
    }

    private void printTags(OSMEntity entity) {
        if (entity.hasNoTags()) {
            return;
        }
        for (OSMEntity.Tag tag : entity.tags) {
            printStream.print(tag.key);
            printStream.print("=");
            printStream.print(tag.value);
            printStream.print(";");
        }
    }

    @Override
    public void writeBegin() throws IOException {
        printStream.print("--- BEGINNING OF OSM TEXT OUTPUT ---\n");
    }

    @Override
    public void setReplicationTimestamp(long secondsSinceEpoch) {
        // TODO IMPLEMENT
    }

    @Override
    public void writeNode(long nodeId, Node node) {
        printStream.print("N ");
        printStream.print(nodeId);
        printStream.print(' ');
        printStream.printf(Locale.US, "%2.6f", node.getLat());
        printStream.print(' ');
        printStream.printf(Locale.US, "%3.6f", node.getLon());
        printStream.print(' ');
        printTags(node);
        printStream.print('\n');
    }

    @Override
    public void writeWay(long wayId, Way way) {
        printStream.print("W ");
        printStream.print(wayId);
        printStream.print(' ');
        printTags(way);
        printStream.print('\n');
//        for (long nodeId : way.nodes) {
//            printNode(nodeId);
//        }
    }

    @Override
    public void writeRelation(long relationId, Relation relation) {
        printStream.print("R ");
        printStream.print(relationId);
        printStream.print(' ');
        printTags(relation);
        printStream.print('\n');
    }

    @Override
    public void writeEnd() throws IOException {
        printStream.print("--- END OF OSM TEXT OUTPUT ---");
    }

}
