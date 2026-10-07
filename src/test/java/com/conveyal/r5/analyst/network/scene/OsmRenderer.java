package com.conveyal.r5.analyst.network.scene;

import com.conveyal.osmlib.Node;
import com.conveyal.osmlib.OSM;
import com.conveyal.osmlib.Way;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/// Render a Scene's streets and paths into an OSM object with MapDB memory or file backed storage.
/// Explicitly declared road junctions are realized as OSM nodes. Every point in the scene that is
/// associated with a SceneJunction instance becomes an OSM node that appears in multiple ways, which will
/// be marked as an intersection and yield separate edges in the resulting TransportNetwork.
/// Points that are not associated with SceneJunctions always yield non-shared nodes, so crossings and even
/// coincident points that are not associated with the same junction are rendered as disconnected overpasses.
class OsmRenderer {

    /// Render the scene to an OSM object backed by dbFile, or in heap memory if that parameter is null.
    static OSM render (Scene scene, File dbFile) {
        OSM osm = dbFile == null ? OSM.newWritableInMemory() : OSM.newWritableFile(dbFile);
        // Tell StreetLayer.loadFromOsm that intersections are already marked; we mark them below.
        osm.intersectionDetection = true;
        Map<SceneJunction, Long> nodeForJunction = new HashMap<>();
        long nextNodeId = 1;
        long nextWayId = 1;
        for (SceneWay sceneWay : scene.ways) {
            long[] nodeIds = new long[sceneWay.points.size()];
            for (int i = 0; i < nodeIds.length; i++) {
                SceneWay.Point point = sceneWay.points.get(i);
                if (point.junction != null && nodeForJunction.containsKey(point.junction)) {
                    nodeIds[i] = nodeForJunction.get(point.junction);
                    continue;
                }
                long nodeId = nextNodeId++;
                Node node = new Node();
                node.setLatLon(scene.latForY(point.y), scene.lonForXY(point.x, point.y));
                osm.nodes.put(nodeId, node);
                if (point.junction != null) {
                    nodeForJunction.put(point.junction, nodeId);
                    osm.intersectionNodes.add(nodeId);
                }
                nodeIds[i] = nodeId;
            }
            Way osmWay = new Way();
            osmWay.nodes = nodeIds;
            sceneWay.tags.forEach(osmWay::addTag);
            osm.ways.put(nextWayId++, osmWay);
        }
        return osm;
    }

}
