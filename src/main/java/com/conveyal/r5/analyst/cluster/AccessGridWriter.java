package com.conveyal.r5.analyst.cluster;

import com.conveyal.r5.profile.FastRaptorWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * Random-access access grid writer - write arrays into offsets in an access grid format file (3-d array)
 *  * Access grids look like this:
 * Header (ASCII text "ACCESSGR") (note that this header is eight bytes, so the full grid can be mapped into a
 *   Javascript typed array if desired)
 * Version, 4-byte integer
 * (4 byte int) Web mercator zoom level
 * (4 byte int) west (x) edge of the grid, i.e. how many pixels this grid is east of the left edge of the world
 * (4 byte int) north (y) edge of the grid, i.e. how many pixels this grid is south of the top edge of the world
 * (4 byte int) width of the grid in pixels
 * (4 byte int) height of the grid in pixels
 * (4 byte int) number of values per pixel
 * (repeated 4-byte int) values of each pixel in row major order. Values within a given pixel are delta coded.
 */
public class AccessGridWriter {
    public static final Logger LOG = LoggerFactory.getLogger(GridResultAssembler.class);

    /** The version of the access grids we produce */
    public static final int VERSION = 0;

    /** The version of the origins we consume */
    public static final int ORIGIN_VERSION = 0;

    /** The offset to get to the data section of the access grid file */
    public static final long HEADER_SIZE = 9 * 4;

    private BufferAbstraction buffer;

    // store as longs so we can use with impunity without fear of overflow
    private final long zoom, west, north, width, height, nValuesPerPixel;

    /**
     * Create a new in-memory access grid writer for a width x height x nValuesPerPixel array.
     */
    public AccessGridWriter (int zoom, int west, int north, int width, int height, int nValuesPerPixel) throws IOException {
        this.zoom = zoom;
        this.west = west;
        this.north = north;
        this.width = width;
        this.height = height;
        this.nValuesPerPixel = nValuesPerPixel;

        long nBytes = (long) width * height * nValuesPerPixel * 4 + HEADER_SIZE;
        if (nBytes > Integer.MAX_VALUE) {
            throw new RuntimeException("Grid size exceeds 31-bit addressable space.");
        }
        this.buffer = new BufferAbstraction((int)nBytes);
        buffer.writeString(0, "ACCESSGR");
        buffer.writeInt(8, VERSION);
        buffer.writeInt(12, zoom);
        buffer.writeInt(16, west);
        buffer.writeInt(20, north);
        buffer.writeInt(24, width);
        buffer.writeInt(28, height);
        buffer.writeInt(32, nValuesPerPixel);
    }

    public void writePixel (int x, int y, int[] pixelValues) throws IOException {
        if (pixelValues.length != nValuesPerPixel) throw new IllegalArgumentException("Incorrect pixel size!");

        long index1d =  (y * width + x) * nValuesPerPixel * 4 + 36;

        int prev = 0;
        for (int i : pixelValues) {
            buffer.writeInt(index1d, i - prev);
            prev = i;
            index1d += 4;
        }
    }

    public byte[] getBytes () {
        return buffer.getBytes();
    }

    // TODO confirm why we need this homemade implementation.
    // I think we can just remove this abstraction and write into an in-memory array.
    // It looks like it was to allow writing disk or memory-based grids interchangeably.
    // RandomAccessFile supports the DataInput and DataOutput interfaces. The issue seems to be that this output
    // data will be read by Javascript, where typed arrays follow the endianness of the underlying processor
    // architecture. That is uniformly little-endian these days, but unfortunately Java data output is big-endian.
    // "The way to address this in Java is via NIO and ByteBuffer, using native byte order instead of the default."
    // Also consider that the destinations are always streamed in in order! So this doesn't even need to be random access.
    private static class BufferAbstraction {

        private byte[] buffer;

        public BufferAbstraction (int length) throws IOException {
            this.buffer = new byte[length];
            // Fill the buffer with UNREACHED so that if a destination is not filled in, it's automatically UNREACHED.
            // The header is a multiple of four bytes in length.
            for (int i = 0; i < length; i += 4) {
                writeInt(i, FastRaptorWorker.UNREACHED);
            }
        }

        public void writeInt (long offset, int value) throws IOException {
            byte a = (byte) value;
            byte b = (byte) (value >> 8);
            byte c = (byte) (value >> 16);
            byte d = (byte) (value >> 24);
            int offsetInt = (int) offset;
            buffer[offsetInt] = a;
            buffer[offsetInt + 1] = b;
            buffer[offsetInt + 2] = c;
            buffer[offsetInt + 3] = d;
        }

        public void writeString (long offset, String value) throws IOException {
            int offsetInt = (int) offset;
            byte[] bytes = value.getBytes();
            for (byte byt : bytes) {
                buffer[offsetInt++] = byt;
            }
        }

        public byte[] getBytes () {
            return buffer;
        }

    }
}
