package com.conveyal.osmlib;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * Reads a stream of VEX data, constructing OSM model objects from the stream and pushing them through to a sink.
 * This is neither threadsafe nor reentrant! Create one instance of this decoder per decode operation.
 */
public class VexInput implements OSMEntitySource {

    private static final Logger LOG = LoggerFactory.getLogger(VexInput.class);

    /* For reading varints out of the decompressed VEX blocks. */
    private VarIntInputStream vin;

    /* Persistent values for delta coding. */
    private long id, ref, prevFixedLat, prevFixedLon;

    /* The stream of compressed VEX blocks. */
    private InputStream vexStream;

    /* The OSM entities reconstructed from the VEX stream will be pushed through to this sink. */
    private OSMEntitySink entitySink;

    private long nNodesRead = 0;

    private long nWaysRead = 0;

    private long nRelationsRead = 0;

    public VexInput(InputStream vexStream) {
        this.vexStream = vexStream;
    }

    @Override
    public void copyTo(OSMEntitySink sink) throws IOException {
        entitySink = sink;
        LOG.info("Reading VEX format...");
        entitySink.writeBegin();
        DeflatedBlockReader blockReader = new DeflatedBlockReader(vexStream);
        int n = 0;
        while (true) {
            VEXBlock block = blockReader.nextBlock();
            if (block == VEXBlock.END_BLOCK) {
                break;
            }
            vin = new VarIntInputStream(new ByteArrayInputStream(block.data));
            // Decode the deflated byte stream into OSM entities and push them through to the sink.
            readBlock(block.entityType, block.nEntities);
            LOG.info("Processed {} blocks", ++n);
        }
        LOG.info("Done reading VEX format.");
        LOG.info("Read {} nodes, {} ways, {} relations.", nNodesRead, nWaysRead, nRelationsRead);
        entitySink.writeEnd();
    }

    public void readBlock(int entityType, int nEntitiesExpected) throws IOException {
        // Reset delta coding fields
        id = ref = prevFixedLat = prevFixedLon = 0;
        // TODO limit number of entities per block ?
        for (int i = 0 ; i < nEntitiesExpected; i++) {
            switch (entityType) {
                case VexFormat.VEX_NODE:
                    readNode();
                    break;
                case VexFormat.VEX_WAY:
                    readWay();
                    break;
                case VexFormat.VEX_RELATION:
                    readRelation();
                    break;
                default:
                    throw new RuntimeException("Unrecognized block type. Corrupt VEX data.");
            }
        }
        // TODO check that byte stream is exhausted, number of entities matches expected.
    }

    public List<OSMEntity.Tag> readTags() throws IOException {
        OSMEntity tagged = new Node();
        int nTags = vin.readUInt32();
        if (nTags > 500) {
            throw new RuntimeException(String.format("Entity has %d tags, this looks like a corrupted file.", nTags));
        }
        for (int i = 0; i < nTags; i++) {
            String key = vin.readString();
            String val = vin.readString();
            tagged.addTag(key, val);
        }
        return tagged.tags;
    }

    public void readNode() throws IOException {
        /* Create a new instance each time because we don't know if this is going in a MapDB or a normal Map. */
        Node node = new Node();
        long idDelta = vin.readSInt64();
        id += idDelta;
        node.tags = readTags();
        node.fixedLat = (int) (prevFixedLat + vin.readSInt64());
        node.fixedLon = (int) (prevFixedLon + vin.readSInt64());
        prevFixedLat = node.fixedLat;
        prevFixedLon = node.fixedLon;
        nNodesRead++;
        entitySink.writeNode(id, node);
    }

    public void readWay() throws IOException {
        /* Create a new instance each time because we don't know if this is going in a MapDB or a normal Map. */
        Way way = new Way();
        long idDelta = vin.readSInt64();
        id += idDelta;
        way.tags = readTags();
        int nNodes = vin.readUInt32();
        way.nodes = new long[nNodes];
        for (int i = 0; i < nNodes; i++) {
            ref += vin.readSInt64();
            way.nodes[i] = ref;
        }
        nWaysRead++;
        entitySink.writeWay(id, way);
    }

    private static OSMEntity.Type[] memberTypeForOrdinal = OSMEntity.Type.values();

    public void readRelation() throws IOException {
        /* Create a new instance each time because we don't know if this is going in a MapDB or a normal Map. */
        Relation relation = new Relation();
        long idDelta = vin.readSInt64();
        id += idDelta;
        relation.tags = readTags();
        int nMembers = vin.readUInt32();
        for (int i = 0; i < nMembers; i++) {
            Relation.Member member = new Relation.Member();
            member.id = vin.readSInt64();
            member.type = memberTypeForOrdinal[vin.readUInt32()]; // FIXME bad, assign specific numbers
            member.role = vin.readString();
            relation.members.add(member);
        }
        nRelationsRead++;
        entitySink.writeRelation(id, relation);
        //System.out.println(id + " " + relation.toString());
    }

}
