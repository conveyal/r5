package com.conveyal.osmlib;

import com.google.common.primitives.Ints;
import com.google.protobuf.ByteString;
import com.google.protobuf.GeneratedMessageLite;
import org.openstreetmap.osmosis.osmbinary.Fileformat;
import org.openstreetmap.osmosis.osmbinary.Osmformat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.SynchronousQueue;
import java.util.zip.Deflater;

/**
 * Consumes OSM entity objects and writes a stream of PBF data blocks to the specified output stream.
 * This is neither threadsafe nor reentrant! Create one instance of this encoder per encode operation.
 */
public class PBFOutput implements OSMEntitySink, Runnable {

    private static final Logger LOG = LoggerFactory.getLogger(PBFOutput.class);

    /** The underlying output stream where VEX data will be written. */
    private OutputStream downstream;

    /** The replication timestamp to record in the PBF file. Should be set before writing begins. */
    private long timestamp;

    /** Values retained from one entity to the next within a block for delta decoding. */
    private long prevId, prevFixedLat, prevFixedLon;

    /** The entity type of the current block to enforce grouping of entities by type. null means no type yet. */
    private OSMEntity.Type currEntityType = null;

    /* Per-block state */

    private final StringTable stringTable = new StringTable();

    private int nEntitiesInBlock;

    private Osmformat.PrimitiveGroup.Builder primitiveGroupBuilder;

    private Osmformat.DenseNodes.Builder denseNodesBuilder;

    private Thread writerThread = null;

    /** Construct a new PBF output encoder which writes to the given downstream OutputStream. */
    public PBFOutput(OutputStream downstream) {
        this.downstream = downstream;
    }

    /** Reset the inter-entity delta coding values and set up a new block. */
    private void beginBlock(OSMEntity.Type eType) throws IOException {
        prevId = prevFixedLat = prevFixedLon = nEntitiesInBlock = 0;
        stringTable.clear();
        primitiveGroupBuilder = Osmformat.PrimitiveGroup.newBuilder();
        if (eType == OSMEntity.Type.NODE) {
            denseNodesBuilder = Osmformat.DenseNodes.newBuilder();
        }
    }

    /** We always add one primitive group of less that 8k elements to each primitive block. */
    private void endBlock () {
        if (nEntitiesInBlock > 0) {
            if (currEntityType == OSMEntity.Type.NODE) {
                primitiveGroupBuilder.setDense(denseNodesBuilder);
            }
            // Pass the block off to the compression/writing thread
            try {
                Osmformat.PrimitiveBlock primitiveBlock = Osmformat.PrimitiveBlock.newBuilder()
                        .setStringtable(stringTable.toBuilder()).addPrimitivegroup(primitiveGroupBuilder).build();
                synchronousQueue.put(primitiveBlock);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }

    /** @param block is either a PrimitiveBlock or a HeaderBlock */
    private void writeOneBlob(GeneratedMessageLite block) {

        // FIXME lotsa big copies going on here

        String blobTypeString;
        if (block instanceof Osmformat.HeaderBlock) {
            blobTypeString = "OSMHeader";
        } else if (block instanceof Osmformat.PrimitiveBlock) {
            blobTypeString = "OSMData";
        } else {
            throw new AssertionError("block must be either a header block or a primitive block.");
        }

        Fileformat.Blob.Builder blobBuilder = Fileformat.Blob.newBuilder();
        byte[] serializedBlock = block.toByteArray();
        byte[] deflatedBlock = new byte[serializedBlock.length];
        int deflatedSize = deflate(serializedBlock, deflatedBlock);
        if (deflatedSize < 0) {
            LOG.debug("Deflate did not reduce the size of a block. Saving it uncompressed.");
            blobBuilder.setRaw(ByteString.copyFrom(serializedBlock));
        } else {
            blobBuilder.setZlibData(ByteString.copyFrom(deflatedBlock, 0, deflatedSize));
            blobBuilder.setRawSize(serializedBlock.length);
        }
        byte[] serializedBlob = blobBuilder.build().toByteArray();

        Fileformat.BlobHeader blobHeader = Fileformat.BlobHeader.newBuilder()
                .setType(blobTypeString).setDatasize(serializedBlob.length).build();
        byte[] serializedBlobHeader = blobHeader.toByteArray();
        try {
            // "Returns a big-endian representation of value in a 4-element byte array"
            downstream.write(Ints.toByteArray(serializedBlobHeader.length));
            downstream.write(serializedBlobHeader);
            downstream.write(serializedBlob);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

    }

    /**
     * Called at the beginning of each node, way, or relation to enforce grouping of entities by type.
     * If the entity type has changed since the last entity (except at the beginning of the first block),
     * ends the block and starts a new one of the new type. Each block will contain entities of only a single type.
     */
    private void checkBlockTransition(OSMEntity.Type eType) throws IOException {
        if (currEntityType != eType || nEntitiesInBlock++ >= 8000) {
            if (currEntityType != null) {
                endBlock();
            }
            currEntityType = eType;
            beginBlock(eType);
        }
    }

    /**
     * Deflate the given input data buffer into the given output byte buffer.
     * Used in both PBF and VEX output.
     * @return the deflated size of the data, or -1 if deflate did not reduce the data size.
     */
    public static int deflate (byte[] input, byte[] output) {
        int pos = 0;
        // Do not compress an empty data block, it will spin forever trying to fill the zero-length output buffer.
        if (input.length > 0) {
            Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, false); // include gzip header and checksum
            deflater.setInput(input, 0, input.length);
            deflater.finish(); // There will be no more input after this byte array.
            while (!deflater.finished()) {
                pos += deflater.deflate(output, pos, output.length - pos, Deflater.SYNC_FLUSH);
                if (pos >= input.length) {
                    return -1; // compressed output is bigger than buffer, store uncompressed
                }
            }
        }
        return pos;
    }

    /* OSM DATA SINK INTERFACE */

    @Override
    public void writeBegin() throws IOException {

        LOG.info("Writing PBF format...");

        // Write out a header block
        Osmformat.HeaderBlock.Builder builder = Osmformat.HeaderBlock.newBuilder();
        builder.addRequiredFeatures("DenseNodes").setWritingprogram("Vanilla Extract").build();
        if (timestamp > 0) {
            builder.setOsmosisReplicationTimestamp(timestamp);
        }
        writeOneBlob(builder.build());

        // Start another thread that will handle compression and writing in parallel.
        writerThread = new Thread(this);
        writerThread.start();

    }

    @Override
    public void setReplicationTimestamp(long secondsSinceEpoch) {
        this.timestamp = secondsSinceEpoch;
    }

    @Override
    public void writeEnd() throws IOException {
        // Finish any partially-completed block.
        endBlock();
        // Send a primitive block with no primitive group to the writer thread, signaling it to shut down and clean up.
        try {
            synchronousQueue.put(Osmformat.PrimitiveBlock.getDefaultInstance());
            writerThread.join();
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        LOG.info("Finished writing PBF format.");
    }

    @Override
    public void writeNode(long id, Node node) throws IOException {

        checkBlockTransition(OSMEntity.Type.NODE);

        long idDelta = id - prevId;
        prevId = id;
        denseNodesBuilder.addId(idDelta);

        /* Fixed-precision latitude and longitude */
        long fixedLat = (long) (node.fixedLat); // TODO are longs necessary?
        long fixedLon = (long) (node.fixedLon);
        long fixedLatDelta = fixedLat - prevFixedLat;
        long fixedLonDelta = fixedLon - prevFixedLon;
        prevFixedLat = fixedLat;
        prevFixedLon = fixedLon;
        denseNodesBuilder.addLat(fixedLatDelta).addLon(fixedLonDelta);

        /* Tags for a whole dense node block are stored as: key, val, key, val, 0, key, val, key, val, 0 */
        if (node.tags != null) {
            for (OSMEntity.Tag tag : node.tags) {
                if (tag.value == null) tag.value = "";
                int keyCode = stringTable.getCode(tag.key);
                int valCode = stringTable.getCode(tag.value);
                denseNodesBuilder.addKeysVals(keyCode);
                denseNodesBuilder.addKeysVals(valCode);
            }
        }
        denseNodesBuilder.addKeysVals(0);

    }

    @Override
    public void writeWay(long id, Way way) throws IOException {

        checkBlockTransition(OSMEntity.Type.WAY);
        Osmformat.Way.Builder builder = Osmformat.Way.newBuilder().setId(id);

        /* Tags */
        if (way.tags != null) {
            for (OSMEntity.Tag tag : way.tags) {
                if (tag.value == null) tag.value = "";
                builder.addKeys(stringTable.getCode(tag.key));
                builder.addVals(stringTable.getCode(tag.value));
            }
        }

        /* Node References */
        long prevNodeRef = 0;
        for (long ref : way.nodes) {
            builder.addRefs(ref - prevNodeRef); // delta-coded node references
            prevNodeRef = ref;
        }

        /* TODO Should we trigger the build here or just call with the builder? */
        primitiveGroupBuilder.addWays(builder.build());

    }

    @Override
    public void writeRelation(long id, Relation relation) throws IOException {

        checkBlockTransition(OSMEntity.Type.RELATION);
        Osmformat.Relation.Builder builder = Osmformat.Relation.newBuilder().setId(id);

        /* Tags */
        if (relation.tags != null) {
            for (OSMEntity.Tag tag : relation.tags) {
                if (tag.value == null) tag.value = "";
                builder.addKeys(stringTable.getCode(tag.key));
                builder.addVals(stringTable.getCode(tag.value));
            }
        }

        /* Relation members */
        long lastMemberId = 0;
        for (Relation.Member member : relation.members) {
            builder.addMemids(member.id - lastMemberId); // delta-coded member references
            lastMemberId = member.id;
            builder.addRolesSid(stringTable.getCode(member.role));
            Osmformat.Relation.MemberType memberType;
            if (member.type == OSMEntity.Type.NODE) {
                memberType = Osmformat.Relation.MemberType.NODE;
            } else if (member.type == OSMEntity.Type.WAY) {
                memberType = Osmformat.Relation.MemberType.WAY;
            } else if (member.type == OSMEntity.Type.RELATION) {
                memberType = Osmformat.Relation.MemberType.RELATION;
            } else {
                throw new RuntimeException("Member type was not defined.");
            }
            builder.addTypes(memberType);
        }

        /* TODO Should we trigger the build here or just call with the builder? */
        primitiveGroupBuilder.addRelations(builder.build());

    }

    /** A zero-length BlockingQueue that hands tasks to the compression/writing thread. */
    private final SynchronousQueue<Osmformat.PrimitiveBlock> synchronousQueue = new SynchronousQueue<>();

    /** Runnable interface implementation that compresses and writes output blocks asynchronously. */
    @Override
    public void run() {
        while (true) {
            try {
                Osmformat.PrimitiveBlock block = synchronousQueue.take(); // block until work is available
                if (block.getPrimitivegroupCount() == 0) {
                    break; // a block with no primitive groups tells the writer thread to shut down.
                }
                writeOneBlob(block);
            } catch (InterruptedException ex) {
                LOG.error("Block writer thread was interrupted while waiting for work.");
                break;
            }
        }
        try {
            downstream.flush();
            downstream.close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

}
