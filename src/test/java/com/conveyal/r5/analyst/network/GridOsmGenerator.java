package com.conveyal.r5.analyst.network;

import com.conveyal.gtfs.GTFSFeed;
import com.conveyal.osmlib.Node;
import com.conveyal.osmlib.OSM;
import com.conveyal.osmlib.OSMEntity;
import com.conveyal.osmlib.Way;
import gnu.trove.list.TIntList;
import gnu.trove.list.TLongList;
import gnu.trove.list.array.TIntArrayList;
import gnu.trove.list.array.TLongArrayList;

/**
 * Generates a MapDB backed OSM object, not necessarily to be serialized out to OSM PBF or XML format, but to be fed
 * directly into the R5 network builder. Used to create networks with predictable characteristics in tests.
 */
public class GridOsmGenerator {

    public final GridLayout gridLayout;

    private final OSM osm;

    private long currentWayId = 0;

    public GridOsmGenerator (GridLayout gridLayout) {
        this.gridLayout = gridLayout;
        osm = new OSM(null);
        // osm.intersectionDetection = true;
    }

    public OSM generate () {
        makeNodes();
        makeWays();
        return osm;
    }

    public long nodeId (int x, int y) {
        return y * (gridLayout.widthAndHeightInBlocks + 1) + x;
    }

    public void makeNodes () {
        osm.intersectionDetection = true;
        for (int x = 0; x <= gridLayout.widthAndHeightInBlocks; x++) {
            for (int y = 0; y <= gridLayout.widthAndHeightInBlocks; y++) {
                Node node = new Node();
                double lat = gridLayout.getIntersectionLat(y);
                node.setLatLon(lat, gridLayout.getIntersectionLon(x, lat));
                long nodeId = nodeId(x, y);
                osm.nodes.put(nodeId, node);
                // All nodes are intersections. Normally these are detected on loading, here we mark on creation.
                osm.intersectionNodes.add(nodeId);
            }
        }
    }

    public void makeWays () {
        makeWays(false);
        makeWays(true);
    }

    public void makeWays (boolean horizontal) {
        for (int a = 0; a <= gridLayout.widthAndHeightInBlocks; a++) {
            TLongList nodes = new TLongArrayList();
            for (int b = 0; b <= gridLayout.widthAndHeightInBlocks; b++) {
                nodes.add(horizontal ? nodeId(b, a) : nodeId(a, b));
            }
            String name = (horizontal ? "row " : "col") + a;
            Way way = new Way();
            way.nodes = nodes.toArray();
            way.addTag("name", name);
            way.addTag("highway", "secondary");
            osm.ways.put(currentWayId, way);
            currentWayId++;
        }
    }

}
