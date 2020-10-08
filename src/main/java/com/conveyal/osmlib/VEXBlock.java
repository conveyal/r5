package com.conveyal.osmlib;

import com.google.common.io.ByteStreams;
import com.google.common.primitives.Ints;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Represents a single block of VEX data which is decompressed, but still varint encoded into a byte buffer.
 */
public class VEXBlock {

    private static final Logger LOG = LoggerFactory.getLogger(VEXBlock.class);

    /** This special instance is handed to a writer to indicate there will be no more blocks. */
    public static final VEXBlock END_BLOCK = new VEXBlock();

    /** Large blocks are not better. Stepping size down (32, 16, 8, 4, 2, 1MB) best size is achieved at 1MB. */
    public static final int BUFFER_SIZE = 1024 * 1024;

    /** Header strings for each kind of OSM entity. TODO move this to OSMEntity. */
    private static final byte[][] HEADERS = new byte[][] {
        "VEXN".getBytes(),
        "VEXW".getBytes(),
        "VEXR".getBytes()
    };

    public int entityType;
    public int nEntities;
    public byte[] data;
    public int nBytes;

    /** */
    public void readDeflated(InputStream in) {
        readHeader(in);
        // Only read the compressed block if it has nonzero size and we're not at EOF
        if (entityType != VexFormat.VEX_NONE && nBytes > 0) {
            try {
                byte[] deflatedData = new byte[nBytes];
                ByteStreams.readFully(in, deflatedData);
                inflate(deflatedData);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        LOG.debug("Read block of {} bytes.", nBytes);
        LOG.debug("Contained {} entities with type {}.", nEntities, entityType);
    }

    /** */
    private void readHeader(InputStream in) {
        byte[] fourBytes = new byte[4];
        try {
            int nRead = ByteStreams.read(in, fourBytes, 0, 4);
            if (nRead == 0) {
                // ByteStreams.read attempts to fully read 4 bytes and returns the number of bytes read,
                // which is only less than 4 if upstream.read() returned a negative value (EOF).
                LOG.debug("Hit end of file, no more blocks to read.");
                nBytes = 0;
                entityType = VexFormat.VEX_NONE; // indicates no more blocks
                return;
            }
            String s = new String(fourBytes);
            if (s.equals("VEXN")) {
                entityType = VexFormat.VEX_NODE;
            } else if (s.equals("VEXW")) {
                entityType = VexFormat.VEX_WAY;
            } else if (s.equals("VEXR")) {
                entityType = VexFormat.VEX_RELATION;
            } else {
                LOG.error("Unrecognized block type '{}', aborting VEX read.", entityType);
                throw new RuntimeException("Uncrecoginzed VEX block type.");
            }
            ByteStreams.readFully(in, fourBytes);
            nEntities = Ints.fromByteArray(fourBytes);
            ByteStreams.readFully(in, fourBytes);
            nBytes = Ints.fromByteArray(fourBytes);
            if (nBytes < 0 || nBytes > BUFFER_SIZE) {
                throw new RuntimeException("Block has impossible compressed data size, it is probably corrupted.");
            }
            if (nEntities < 0 || nEntities > BUFFER_SIZE) {
                throw new RuntimeException("Block contains impossible number of entities, it is probably corrupted.");
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void writeDeflated(OutputStream out) {
        byte[] deflatedData = new byte[nBytes]; // FIXME in theory, deflate could make the block larger
        int deflatedSize = PBFOutput.deflate(data, deflatedData);
        if (deflatedSize < 0) {
            throw new RuntimeException("Deflate made a block bigger.");
        }
        try {
            // Header, number of messages and size of compressed data as two 4-byte big-endian ints, compressed data.
            out.write(HEADERS[entityType]);
            out.write(Ints.toByteArray(nEntities));
            out.write(Ints.toByteArray(deflatedSize));
            out.write(deflatedData, 0, deflatedSize);
            LOG.debug("Wrote block of {} bytes.", deflatedSize);
            LOG.debug("Contained {} entities with type {}.", nEntities, entityType);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /** Inflate the given byte buffer into this VEXBlock's data field. */
    private void inflate (byte[] input) {
        data = new byte[BUFFER_SIZE];
        int pos = 0;
        Inflater inflater = new Inflater();
        inflater.setInput(input, 0, input.length);
        try {
            while (!inflater.finished()) {
                pos += inflater.inflate(data, pos, data.length - pos);
            }
        } catch (DataFormatException e) {
            e.printStackTrace();
            pos = 0;
        }
        inflater.end();
        nBytes = pos;
    }

}
