package com.conveyal.r5.analyst.cluster;

import com.conveyal.r5.profile.FastRaptorWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Random-access access grid writer - write arrays into offsets in an access grid format file (3-d array)
 *  * Access grids look like this:
 * Header (ASCII text "TIMEGRID") (note that this header is eight bytes, so the full grid can be mapped into a
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
public class TimeGridWriter {
    public static final Logger LOG = LoggerFactory.getLogger(GridResultAssembler.class);

    /**
     * The offset to get to the data section of the access grid file
     */
    public static final int HEADER_SIZE = 9 * 4;

    public ByteBuffer buffer;

    // store as longs so we can use with impunity without fear of overflow
    private final long zoom, west, north, width, height, nValuesPerPixel;

    /**
     * Create a new in-memory access grid writer for a width x height x nValuesPerPixel array.
     */
    public TimeGridWriter(int zoom, int west, int north, int width, int height, int nValuesPerPixel) throws IOException {
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

        buffer = ByteBuffer.allocate((int) nBytes);
        buffer.order(ByteOrder.LITTLE_ENDIAN);

        buffer.putInt(12, zoom);
        buffer.putInt(16, west);
        buffer.putInt(20, north);
        buffer.putInt(24, width);
        buffer.putInt(28, height);
        buffer.putInt(36, nValuesPerPixel);

    }

    public void initialize(String type, int version){
        if ("TIMEGRID".equals(type)){

            buffer.put(type.getBytes(),0,8);
            buffer.putInt(8, version);
            //Fill a time grid with the default unreachable value
            for (int i = HEADER_SIZE; i < (int) width * height * nValuesPerPixel * 4 + HEADER_SIZE; i += 4) {
                buffer.putInt(i, FastRaptorWorker.UNREACHED);
            }
        } else {
            LOG.warn("Unsupported grid type");
        }
    }

    public void writePixel(int x, int y, int[] pixelValues) throws IOException {
        if (pixelValues.length != nValuesPerPixel)
            throw new IllegalArgumentException("Incorrect number of values per pixel.");

        long index1d = (y * width + x) * nValuesPerPixel * 4 + HEADER_SIZE;

        if (index1d > Integer.MAX_VALUE) {
            throw new RuntimeException("Pixel coordinates are too large for this grid.");
        }

        int prev = 0;
        for (int i : pixelValues) {
            buffer.putInt((int) index1d, i - prev);
            prev = i;
            index1d += 4;
        }
    }
}
