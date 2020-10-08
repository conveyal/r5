package com.conveyal.osmlib;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.util.List;

/**
 * Consumes OSM entity objects and writes a stream of VEX data blocks to a specified output stream.
 * This is neither threadsafe nor reentrant! Create one instance of this encoder per encode operation.
 */
public class VexOutput implements OSMEntitySink {

    private static final Logger LOG = LoggerFactory.getLogger(VexOutput.class);

    /** The underlying output stream where VEX data will be written. */
    private OutputStream downstream;

    /** A message-oriented output stream that will write out blocks of VEX data when its buffer is filled. */
    private DeflatedBlockWriter blockWriter;

    /** The output sink for uncompressed VEX format. */
    private VarIntOutputStream vout;

    /** Values retained from one entity to the next within a block for delta decoding. */
    private long prevId, prevRef, prevFixedLat, prevFixedLon;

    /** The entity type of the current block to enforce grouping of entities by type. */
    private int currEntityType = VexFormat.VEX_NONE;

    /** The replication timestamp to apply to the output. */
    private long timestamp;

    /** Construct a new VEX output encoder which writes to the given downstream OutputStream. */
    public VexOutput(OutputStream downstream) {
        this.downstream = downstream;
    }

    /** Reset the inter-entity delta coding values and set the entity type for a new block. */
    private void beginBlock(int eType) throws IOException {
        prevId = prevRef = prevFixedLat = prevFixedLon = 0;
        blockWriter.setEntityType(eType);
    }

    /**
     * Called at the beginning of each node, way, or relation.
     * Writes the fields common to all OSM entities (ID and tags) and increments the entity counter.
     */
    private void beginEntity(long id, OSMEntity osmEntity) throws IOException {
        long idDelta = id - prevId;
        if (idDelta == 0) {
            LOG.error("The same entity ID is being written twice in a row. This will prematurely terminate a block.");
        }
        vout.writeSInt64(idDelta);
        prevId = id;
        writeTags(osmEntity);
    }

    /**
     * Called at the end of each node, way, or relation. Tells the downstream block writer that it has received a
     * complete message and may now consider writing a block.
     */
    private void endEntity() throws IOException {
        if (blockWriter.endEntity()) {
            // The writer has signaled that a block is finished.
            // Start a new one, resetting the delta coding variables.
            beginBlock(currEntityType);
        }
    }

    /**
     * Called at the beginning of each node, way, or relation to enforce grouping of entities by type.
     * If the entity type has changed since the last entity (except at the beginning of the first block),
     * ends the block and starts a new one of the new type. Each block must contain entities of only a single type.
     * TODO react to intermixing of entity types in the input by holding one working block of each type.
     */
    private void checkBlockTransition(int eType) throws IOException {
        if (currEntityType != eType) {
            if (currEntityType != VexFormat.VEX_NONE) {
                blockWriter.endBlock();
                String type = "entities";
                if (currEntityType == VexFormat.VEX_NODE) type = "nodes";
                if (currEntityType == VexFormat.VEX_WAY) type = "ways";
                if (currEntityType == VexFormat.VEX_RELATION) type = "relations";
            }
            beginBlock(eType);
            currEntityType = eType;
        }
    }

    /**
     * Writes out a list of tags for the given OSM entity. This code is the same for all entity types.
     */
    private void writeTags(OSMEntity tagged) throws IOException {
        List<OSMEntity.Tag> tags = tagged.tags;
        // TODO This could stand a little more abstraction, like List<Tag> getTags()
        if (tagged.tags == null) {
            vout.writeUInt32(0);
        } else {
            vout.writeUInt32(tags.size());
            for (OSMEntity.Tag tag : tagged.tags) {
                if (tag.value == null) tag.value = "";
                vout.writeString(tag.key);
                vout.writeString(tag.value);
            }
        }
    }

    /* OSM DATA SINK INTERFACE */

    @Override
    public void writeBegin() throws IOException {
        LOG.info("Writing VEX format...");
        blockWriter = new DeflatedBlockWriter(downstream);
        vout = new VarIntOutputStream(blockWriter);
    }

    @Override
    public void setReplicationTimestamp(long secondsSinceEpoch) {
        this.timestamp = secondsSinceEpoch;
    }

    @Override
    public void writeEnd() throws IOException {
        blockWriter.endBlock(); // Finish any partially-completed block.
        blockWriter.close(); // Let writing thread complete then close downstream OutputStream.
        LOG.info("Finished writing VEX format.");
    }

    @Override
    public void writeNode(long id, Node node) throws IOException {
        checkBlockTransition(VexFormat.VEX_NODE);
        beginEntity(id, node);
        // plain ints should be fine rather than longs:
        // 2**31 = 2147483648
        // 180e7 = 1800000000.0
        long fixedLat = (long) (node.fixedLat);
        long fixedLon = (long) (node.fixedLon);
        vout.writeSInt64(fixedLat - prevFixedLat);
        vout.writeSInt64(fixedLon - prevFixedLon);
        prevFixedLat = fixedLat;
        prevFixedLon = fixedLon;
        endEntity();
    }

    /**
     * Delta coding node references across ways does help.
     * Resetting the prevRef to zero for each way has been shown to increase size.
     */
    @Override
    public void writeWay(long id, Way way) throws IOException {
        checkBlockTransition(VexFormat.VEX_WAY);
        beginEntity(id, way);
        vout.writeUInt32(way.nodes.length);
        for (long ref : way.nodes) {
            vout.writeSInt64(ref - prevRef);
            prevRef = ref;
        }
        endEntity();
    }

    @Override
    public void writeRelation(long id, Relation relation) throws IOException {
        checkBlockTransition(VexFormat.VEX_RELATION);
        beginEntity(id, relation);
        vout.writeUInt32(relation.members.size());
        for (Relation.Member member : relation.members) {
            vout.writeSInt64(member.id);
            vout.writeUInt32(member.type.ordinal()); // FIXME ordinal is bad. assign specific codes to types.
            vout.writeString(member.role);
        }
        endEntity();
    }

}
