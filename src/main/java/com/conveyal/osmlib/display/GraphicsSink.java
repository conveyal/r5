package com.conveyal.osmlib.display;

import com.conveyal.osmlib.Node;
import com.conveyal.osmlib.OSMEntitySink;
import com.conveyal.osmlib.Relation;
import com.conveyal.osmlib.Way;

import java.awt.*;
import java.awt.geom.Line2D;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;

/**
 *
 */
public class GraphicsSink implements OSMEntitySink {

    Graphics2D g2d;

    public GraphicsSink(Graphics2D g2d) {
        this.g2d = g2d;
    }

    @Override
    public void writeBegin() throws IOException {

    }

    @Override
    public void setReplicationTimestamp(long secondsSinceEpoch) {
        g2d.drawString(Instant.ofEpochSecond(secondsSinceEpoch).atZone(ZoneId.systemDefault()).toString(), 0, 0);
    }

    @Override
    public void writeNode(long id, Node node) throws IOException {
        Shape line = new Line2D.Double(node.getLon(), node.getLat(), node.getLon(), node.getLat());
        g2d.draw(line);
    }

    @Override
    public void writeWay(long id, Way way) throws IOException {

    }

    @Override
    public void writeRelation(long id, Relation relation) throws IOException {

    }

    @Override
    public void writeEnd() throws IOException {

    }

}
