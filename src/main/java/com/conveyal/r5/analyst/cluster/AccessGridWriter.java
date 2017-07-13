package com.conveyal.r5.analyst.cluster;

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

    /** File backing this access grid writer, if it's using a file */
    public final File file;

    private BufferAbstraction buffer;

    // store as longs so we can use with impunity without fear of overflow
    private final long zoom, west, north, width, height, nValuesPerPixel;

    /** Create a new access grid writer for a width x height x nValuesPerPixel array. */
    public AccessGridWriter (int zoom, int west, int north, int width, int height, int nValuesPerPixel) throws IOException {
        this(null, zoom, west, north, width, height, nValuesPerPixel);
    }

    /** Create a file-backed AccessGridWriter */
    public AccessGridWriter (File gridFile, int zoom, int west, int north, int width, int height, int nValuesPerPixel) throws IOException {
        this.zoom = zoom;
        this.west = west;
        this.north = north;
        this.width = width;
        this.height = height;
        this.nValuesPerPixel = nValuesPerPixel;
        this.file = gridFile;

        long nBytes = (long) width * height * nValuesPerPixel * 4 + HEADER_SIZE;
        this.buffer = new BufferAbstraction(gridFile, nBytes);

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

    public void close () throws IOException {
        this.buffer.close();
    }

    public byte[] getBytes () {
        return buffer.getBytes();
    }

    private static class BufferAbstraction {
        private RandomAccessFile file;
        private byte[] buffer;

        public BufferAbstraction (File file, long length) throws IOException {
            if (file != null) {
                this.file = new RandomAccessFile(file, "rw");
                this.file.setLength(length);
                // clear the contents of the file, don't accidentally leak disk contents
                for (long i = 0; i < length; i++) {
                    this.file.writeByte(0);
                }
            } else {
                if (length > Integer.MAX_VALUE) throw new IllegalArgumentException("Attempt to create in memory buffer larger than 2GB");
                this.buffer = new byte[(int) length];
            }
        }

        public void writeInt (long offset, int value) throws IOException {
            byte a = (byte) value;
            byte b = (byte) (value >> 8);
            byte c = (byte) (value >> 16);
            byte d = (byte) (value >> 24);

            if (file != null) {
                file.seek(offset);
                // can't use file.writeInt, as it is in typical Java fashion big-endian . . .
                file.writeByte(a);
                file.writeByte(b);
                file.writeByte(c);
                file.writeByte(d);
            } else {
                int offsetInt = (int) offset;
                buffer[offsetInt] = a;
                buffer[offsetInt + 1] = b;
                buffer[offsetInt + 2] = c;
                buffer[offsetInt + 3] = d;
            }
        }

        public void writeString (long offset, String value) throws IOException {
            if (file != null) {
                file.seek(offset);
                file.writeBytes(value);
            } else {
                int offsetInt = (int) offset;
                byte[] bytes = value.getBytes();
                for (byte byt : bytes) {
                    buffer[offsetInt++] = byt;
                }
            }
        }

        public byte[] getBytes () {
            if (buffer != null) return buffer;
            else throw new UnsupportedOperationException("Attempt to retrieve bytes for on-disk access grid.");
        }

        public void close () throws IOException {
            if (this.file != null) this.file.close();
        }
    }
}
