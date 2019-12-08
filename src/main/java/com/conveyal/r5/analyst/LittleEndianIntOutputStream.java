package com.conveyal.r5.analyst;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;

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

    /** Write an ASCII string as bytes, generally used as a header */
    public void writeAscii(String string) throws IOException {
        byte[] bytes = string.getBytes(Charset.forName("ASCII"));
        out.write(bytes);
    }
}
