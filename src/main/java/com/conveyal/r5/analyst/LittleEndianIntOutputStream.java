package com.conveyal.r5.analyst;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Class to output ints. An order of magnitude faster than Guava LittleEndianDataOutputStream.
 */
public class LittleEndianIntOutputStream extends FilterOutputStream {
    public LittleEndianIntOutputStream(OutputStream out) {
        super(out);
    }

    public void writeInt (int value) throws IOException {
        byte[] bytes = new byte[]{
                (byte) (value & 0xff),
                (byte) (value >> 8 & 0xff),
                (byte) (value >> 16 & 0xff),
                (byte) (value >> 24 & 0xff)
        };

        out.write(bytes);
    }
}
