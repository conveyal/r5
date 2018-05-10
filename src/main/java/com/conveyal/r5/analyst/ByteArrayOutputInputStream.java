package com.conveyal.r5.analyst;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;

/**
 * An OutputStream that buffers everything in memory, allowing the contents of that buffer to be read back out as an
 * InputStream. This works around the fact that ByteArrayOutputStream.toByteArray makes a copy of the backing byte
 * array, doubling memory consumption and wasting time.
 */
public class ByteArrayOutputInputStream extends ByteArrayOutputStream {

    /**
     * @return an input stream wrapping the internal byte buffer. Further writes are not possible.
     */
    public InputStream getInputStream () {
        try {
            this.close();
            InputStream inputStream = new ByteArrayInputStream(buf);
            // Prevent additional writes to the internal buffer.
            buf = null;
            return inputStream;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

}
