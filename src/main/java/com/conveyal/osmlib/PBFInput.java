package com.conveyal.osmlib;
/*
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Lesser General Public License as published by the Free Software Foundation, either version 3
 * of the License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 * even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License along with this program. If
 * not, see <http://www.gnu.org/licenses/>.
 */

import com.conveyal.osmlib.OSMEntity.Type;
import org.openstreetmap.osmosis.osmbinary.BinaryParser;
import org.openstreetmap.osmosis.osmbinary.Osmformat;
import org.openstreetmap.osmosis.osmbinary.file.BlockInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;

/**
 * An OpenStreetMap entity source that reads from the PBF Format. This class implements callbacks for
 * the crosby.binary OSMPBF library. It loads OSM data into the osm-lib model classes, then sends those
 * objects through to the specified OSM entity sink.
 */
public class PBFInput extends BinaryParser implements OSMEntitySource {

    protected static final Logger LOG = LoggerFactory.getLogger(PBFInput.class);

    private long nodeCount = 0;
    private long wayCount = 0;
    private long relationCount = 0;
    private InputStream inputStream;
    private OSMEntitySink entitySink;

    private static final String[] retainKeys = new String[] {
        "highway", "parking", "bicycle", "name"
    };

    public PBFInput(InputStream inputStream) {
        this.inputStream = inputStream;
    }

    // Accepting all tags increases size by about 15 percent when storing all elements.
    // Not storing elements that lack interesting tags reduces size by 80%.
    // return true; DEBUG
    private boolean retainTag(String key) {
        return true;
//        for (String s : retainKeys) {
//            if (s.equals(key)) return true;
//        }
//        return false;
    }

    /** Note that in many PBF files this function is never called because all nodes are dense. */
    @Override
    protected void parseNodes(List<Osmformat.Node> nodes) {
        try {
            for (Osmformat.Node n : nodes) {
                if (nodeCount++ % 10000000 == 0) {
                    LOG.info("node {}", human(nodeCount));
                }
                Node node = new Node(parseLat(n.getLat()), parseLon(n.getLon()));
                for (int k = 0; k < n.getKeysCount(); k++) {
                    String key = getStringById(n.getKeys(k));
                    String val = getStringById(n.getVals(k));
                    if (retainTag(key)) node.addTag(key, val);
                }
                entitySink.writeNode(n.getId(), node);
            }
        } catch (IOException ex) {
            LOG.error("An I/O exception occurred in the OSM entity sink.");
            ex.printStackTrace();
        }
    }

    /**
     * Nodes are usually stored this way. Dense nodes use parallel arrays (a column store) to defeat typical
     * Protobuf message structure.
     */
    @Override
    protected void parseDense(Osmformat.DenseNodes nodes) {
        long lastId = 0, lastLat = 0, lastLon = 0;
        int kv = 0; // index into the keysvals array
        try {
            for (int n = 0; n < nodes.getIdCount(); n++) {
                if (nodeCount++ % 5000000 == 0) {
                    LOG.info("node {}", human(nodeCount));
                }
                Node node = new Node();
                long id = nodes.getId(n) + lastId;
                long lat = nodes.getLat(n) + lastLat;
                long lon = nodes.getLon(n) + lastLon;
                lastId = id;
                lastLat = lat;
                lastLon = lon;
                node.setLatLon(parseLat(lat), parseLon(lon));
                // Check whether any node has tags.
                if (nodes.getKeysValsCount() > 0) {
                    while (nodes.getKeysVals(kv) != 0) {
                        int kid = nodes.getKeysVals(kv++);
                        int vid = nodes.getKeysVals(kv++);
                        String key = getStringById(kid);
                        String val = getStringById(vid);
                        if (retainTag(key)) node.addTag(key, val);
                    }
                    kv++; // Skip over the '0' delimiter.
                }
                entitySink.writeNode(id, node);
            }
        } catch (IOException ex) {
            LOG.error("An I/O exception occurred in the OSM entity sink.");
            ex.printStackTrace();
        }
    }

    @Override
    protected void parseWays(List<Osmformat.Way> ways) {
        try {
            for (Osmformat.Way w : ways) {
                if (wayCount++ % 1000000 == 0) {
                    LOG.info("way {}", human(wayCount));
                }
                Way way = new Way();
                /* Handle tags */
                for (int k = 0; k < w.getKeysCount(); k++) {
                    String key = getStringById(w.getKeys(k));
                    String val = getStringById(w.getVals(k));
                    if (retainTag(key)) way.addTag(key, val);
                }
                /* Handle nodes */
                List<Long> rl = w.getRefsList();
                long[] nodes = new long[rl.size()];
                long ref = 0;
                for (int n = 0; n < nodes.length; n++) {
                    ref += rl.get(n);
                    nodes[n] = ref;
                }
                way.nodes = nodes;
                entitySink.writeWay(w.getId(), way);
            }
        } catch (IOException ex) {
            LOG.error("An I/O exception occurred in the OSM entity sink.");
            ex.printStackTrace();
        }
    }

    @Override
    protected void parseRelations(List<Osmformat.Relation> rels) {
        try {
            for (Osmformat.Relation r : rels) {
                if (relationCount++ % 100000 == 0) {
                    LOG.info("relation {}", human(relationCount));
                }
                Relation rel = new Relation();
                /* Handle Tags */
                for (int k = 0; k < r.getKeysCount(); k++) {
                    String key = getStringById(r.getKeys(k));
                    String val = getStringById(r.getVals(k));
                    if (retainTag(key)) rel.addTag(key, val);
                }
                /* Handle members of the relation */
                long mid = 0; // member ids, delta coded
                for (int m = 0; m < r.getMemidsCount(); m++) {
                    Relation.Member member = new Relation.Member();
                    mid += r.getMemids(m);
                    member.id = mid;
                    member.role = getStringById(r.getRolesSid(m));
                    switch (r.getTypes(m)) {
                    case NODE:
                        member.type = Type.NODE;
                        break;
                    case WAY:
                        member.type = Type.WAY;
                        break;
                    case RELATION:
                        member.type = Type.RELATION;
                        break;
                    default:
                        LOG.error("Relation type is unexpected.");
                    }
                    rel.members.add(member);
                }
                entitySink.writeRelation(r.getId(), rel);
            }
        } catch (IOException ex) {
            LOG.error("An I/O exception occurred in the OSM entity sink.");
            ex.printStackTrace();
        }
    }

    @Override
    public void parse(Osmformat.HeaderBlock block) {
        for (String s : block.getRequiredFeaturesList()) {
            if (s.equals("OsmSchema-V0.6")) {
                continue; // We can parse this.
            }
            if (s.equals("DenseNodes")) {
                continue; // We can parse this.
            }
            throw new IllegalStateException("File requires unknown feature: " + s);
        }
        if (block.hasOsmosisReplicationTimestamp()) {
            long timestamp = block.getOsmosisReplicationTimestamp();
            LOG.info("PBF file has a replication timestamp of {}", Instant.ofEpochSecond(timestamp));
            entitySink.setReplicationTimestamp(timestamp);
        } else {
            LOG.info("PBF file has no replication timestamp.");
        }
    }

    @Override
    public void complete() {
        LOG.info("Done parsing PBF.");
        LOG.info("Read {} nodes, {} ways, {} relations.", nodeCount, wayCount, relationCount);
    }

    private static String human(long n) {
        if (n > 1000000)
            return String.format("%.1fM", n / 1000000.0);
        if (n > 1000)
            return String.format("%dk", n / 1000);
        else
            return String.format("%d", n);
    }

    @Override
    public void copyTo(OSMEntitySink sink) throws IOException {
        entitySink = sink;
        entitySink.writeBegin();
        new BlockInputStream(inputStream, this).process();
        entitySink.writeEnd();
    }

}
