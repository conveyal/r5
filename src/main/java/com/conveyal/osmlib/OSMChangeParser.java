package com.conveyal.osmlib;

import gnu.trove.list.TLongList;
import gnu.trove.list.array.TLongArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/**
 * A lenient streaming XML parser that reads OSM change files and applies the changes they contain to an OSM database.
 * It seems like a good idea to abstract out a ChangeSink interface that extends the basic OSM entity sink.
 * However, we need non-streaming behavior here: we want to index all the new ways after applying an entire diff
 * because we have no guarantee that the nodes and ways are coherent at some point partway through the changes.
 */
public class OSMChangeParser extends DefaultHandler {

    private static final Logger LOG = LoggerFactory.getLogger(OSMChangeParser.class);

    OSM osm;
    boolean inDelete = false; // if false, assume we're in add or modify
    OSMEntity entity;
    long id;
    int nParsed = 0;
    TLongList nodeRefs = new TLongArrayList();
    TLongList waysModified = new TLongArrayList();

    public OSMChangeParser(OSM osm) {
        this.osm = osm;
    }

    public void startElement(String uri, String localName, String qName, Attributes attributes)
            throws SAXException {

        String idString = attributes.getValue("id");

        if (qName.equalsIgnoreCase("ADD") || qName.equalsIgnoreCase("MODIFY")) {
            inDelete = false;
        } else if (qName.equalsIgnoreCase("DELETE")) {
            inDelete = true;
        } else if (qName.equalsIgnoreCase("NODE")) {
            Node node = new Node();
            double lat = Double.parseDouble(attributes.getValue("lat"));
            double lon = Double.parseDouble(attributes.getValue("lon"));
            node.setLatLon(lat, lon);
            entity = node;
            id = idString == null ? -1 : Long.parseLong(idString);
        } else if (qName.equalsIgnoreCase("WAY")) {
            Way way = new Way();
            entity = way;
            nodeRefs.clear();
            id = idString == null ? -1 : Long.parseLong(idString);
        } else if (qName.equalsIgnoreCase("RELATION")) {
            Relation relation = new Relation();
            entity = relation;
            id = idString == null ? -1 : Long.parseLong(idString);
        } else if (qName.equalsIgnoreCase("TAG")) {
            entity.addTag(attributes.getValue("k"), attributes.getValue("v"));
        } else if (qName.equalsIgnoreCase("ND")) {
            nodeRefs.add(Long.parseLong(attributes.getValue("ref")));
        }
    }

    public void endElement(String uri, String localName, String qName) throws SAXException {

        nParsed++;
        if (nParsed % 1000000 == 0) {
            LOG.info(" {}M applied", nParsed / 1000000);
        }

        if (qName.equalsIgnoreCase("DELETE")) {
            inDelete = false;
            return;
        } else if (qName.equalsIgnoreCase("NODE")) {
            if (inDelete) {
                osm.nodes.remove(id);
            } else {
                osm.nodes.put(id, (Node) entity);
            }
        } else if (qName.equalsIgnoreCase("WAY")) {
            if (inDelete) {
                // Remove from index before removing the way itself. This allows the remove method to locate the way.
                osm.unIndexWay(id);
                osm.ways.remove(id);
            } else {
                Way way = ((Way)entity);
                way.nodes = nodeRefs.toArray();
                osm.ways.put(id, way);
                waysModified.add(id); // record that this way was modified for later re-indexing.
            }
        } else if (qName.equalsIgnoreCase("RELATION")) {
            if (inDelete) {
                osm.relations.remove(id);
            } else {
                osm.relations.put(id, (Relation) entity);
            }
        }
    }

    @Override
    public void startDocument() {
        waysModified.clear();
        nParsed = 0;
        inDelete = false;
    }

    @Override
    public void endDocument() {
        // After the entire diff has been applied, re-index all the ways that were added or modified.
        if (!waysModified.isEmpty()) {
            LOG.debug("Indexing modified ways...");
            for (int w = 0; w < waysModified.size(); w++) {
                // TODO unless we are doing snapshots and transactions, we should unindex after indexing the new one?
                osm.unIndexWay(id);
                osm.indexWay(waysModified.get(w), null);
            }
        }
    }

}