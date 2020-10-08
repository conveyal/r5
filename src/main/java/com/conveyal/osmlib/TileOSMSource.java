package com.conveyal.osmlib;

import gnu.trove.iterator.TLongIterator;
import gnu.trove.list.TLongList;
import gnu.trove.list.array.TLongArrayList;
import gnu.trove.set.TLongSet;
import gnu.trove.set.hash.TLongHashSet;
import org.mapdb.Fun;
import org.mapdb.Fun.Tuple3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.NavigableSet;
import java.util.Set;

/** An OSM source that pulls web Mercator tiles out of a disk-backed OSM store. */
public class TileOSMSource implements OSMEntitySource {

    protected static final Logger LOG = LoggerFactory.getLogger(TileOSMSource.class);

    private int minX, minY, maxX, maxY;

    private OSM osm;

    public TileOSMSource (OSM osm) {
        this.osm = osm;
    }

    public void setTileRange(int minX, int minY, int maxX, int maxY) {
        if (minX > maxX || minY > maxY) {
            throw new IllegalArgumentException("Min must be smaller or equal to max.");
        }
        this.minX = minX;
        this.minY = minY;
        this.maxX = maxX;
        this.maxY = maxY;
    }

    public void setBoundingBox(double minLat, double minLon, double maxLat, double maxLon) {
        WebMercatorTile minTile = new WebMercatorTile(minLat, minLon);
        WebMercatorTile maxTile = new WebMercatorTile(maxLat, maxLon);
        // Note that y tile numbers are increasing in the opposite direction of latitude (from north to south)
        // so the parameter order min,max.max,min is intentional.
        setTileRange(minTile.xtile, maxTile.ytile, maxTile.xtile, minTile.ytile);

    }

    public void copyTo (OSMEntitySink sink) {
        // Avoid writing out shared/intersection nodes more than once. Besides being wasteful, the first node in one way
        // may be the last node in the previous way output, which would create a node ID delta of zero and prematirely
        // end the block.
        NodeTracker nodesSeen = new NodeTracker();
        TLongSet relationsSeen = new TLongHashSet();

        try {
            sink.writeBegin();
            for (int pass = 0; pass < 2; pass++) {
                for (int x = minX; x <= maxX; x++) {
                    // SortedSet provides one-dimensional ordering and iteration. Tuple3 gives an odometer-like ordering.
                    // Therefore we must vary one of the dimensions "manually". Consider a set containing all the
                    // integers from 00 to 99 at 2-tuples. The range from (1,1) to (2,2) does not contain the four
                    // elements (1,1) (1,2) (2,1) (2,2). It contains the elements (1,1) (1,2) (1,3) (1,4) ... (2,2).
                    @SuppressWarnings("unchecked")
                    NavigableSet<Tuple3<Integer, Integer, Long>> xSubset = osm.index.subSet(
                            new Tuple3(x, minY, null), true, // inclusive lower bound, null tests lower than anything
                            new Tuple3(x, maxY, Fun.HI), true  // inclusive upper bound, HI tests higher than anything
                    );
                    for (Tuple3<Integer, Integer, Long> item : xSubset) {
                        long wayId = item.c;
                        Way way = osm.ways.get(wayId);
                        if (way == null) {
                            LOG.error("Way {} is not available.", wayId);
                            continue;
                        }
                        if (pass == 0) { // Nodes
                            for (long nodeId : way.nodes) {
                                if (nodesSeen.contains(nodeId)) continue;
                                Node node = osm.nodes.get(nodeId);
                                if (node == null) {
                                    LOG.error("Way references a node {} that was not loaded.", nodeId);
                                } else {
                                    sink.writeNode(nodeId, node);
                                    nodesSeen.add(nodeId);

                                    // check if this node is part of any relations
                                    Set<Fun.Tuple2<Long, Long>> relationsForNode = osm.relationsByNode.subSet(
                                            new Fun.Tuple2(wayId, null),
                                            new Fun.Tuple2(wayId, Fun.HI));

                                    for (Fun.Tuple2<Long, Long> idx: relationsForNode) {
                                        relationsSeen.add(idx.b);
                                    }
                                }
                            }
                        } else if (pass == 1) {
                            sink.writeWay(wayId, way);

                            Set<Fun.Tuple2<Long, Long>> relationsForWay = osm.relationsByWay.subSet(
                                    new Fun.Tuple2(wayId, null),
                                    new Fun.Tuple2(wayId, Fun.HI));
                            // check if this way is part of any relations
                            for (Fun.Tuple2<Long, Long> idx : relationsForWay) {
                                relationsSeen.add(idx.b);
                            }
                        }
                    }
                }

                // write relations all at one fell swoop
                // first see if there are any relations that are referred by other relations.
                TLongList addedRelations = new TLongArrayList();
                do {
                    addedRelations.clear();

                    // we have to do this recursively as a relation could refer to a relation that in turn refers to a
                    // relation. Note that since we are using a set we can't loop infinitely if there is a cycle or self
                    // referential relation.
                    for (TLongIterator it = relationsSeen.iterator(); it.hasNext();) {
                        long relation = it.next();

                        Set<Fun.Tuple2<Long, Long>> relationsForRelation = osm.relationsByRelation.subSet(
                                new Fun.Tuple2(relation, null),
                                new Fun.Tuple2(relation, Fun.HI));

                        for (Fun.Tuple2<Long, Long> idx : relationsForRelation) {
                            if (!relationsSeen.contains(relation)) addedRelations.add(idx.b);
                        }
                    }

                    relationsSeen.addAll(addedRelations);
                } while (!addedRelations.isEmpty());

                for (TLongIterator it = relationsSeen.iterator(); it.hasNext();) {
                    long relId = it.next();
                    sink.writeRelation(relId, osm.relations.get(relId));
                }
            }
            sink.writeEnd();
        } catch (IOException ex) {
            throw new RuntimeException("I/O exception while writing tiled OSM data.", ex);
        }
    }

}
