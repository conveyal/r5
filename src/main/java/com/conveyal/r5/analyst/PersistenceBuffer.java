package com.conveyal.r5.analyst;

import com.conveyal.r5.common.JsonUtilities;
import com.google.common.io.LittleEndianDataOutputStream;

import java.io.DataOutput;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.GZIPOutputStream;

/**
 * This provides the functionality of a DataOutputStream for writing out files, but the data are automatically
 * compressed into an in-memory buffer. This makes it possible to know the length before upload, which is required
 * by AWS S3. It also works around a limitation of ByteArrayOutputStream that requires the backing byte array to be
 * copied to create an InputStream. It uses the little-endian representation of integers so they can be read
 * straight out of a typed array in Javascript on any little-endian architecture (nearly all processors these days).
 */
public class PersistenceBuffer {

    private boolean doneWriting = false;

    private static final String DEFAULT_MIME_TYPE = "application/octet-stream";

    private String mimeType = DEFAULT_MIME_TYPE;

    private final LittleEndianDataOutputStream littleEndianDataOutputStream;

    private final OutputStream outputStream;

    private final ByteArrayOutputInputStream buffer;

    public PersistenceBuffer() {
        try {
            buffer = new ByteArrayOutputInputStream();
            outputStream = new GZIPOutputStream(buffer);
            littleEndianDataOutputStream = new LittleEndianDataOutputStream(outputStream);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static PersistenceBuffer serializeAsJson (Object object) {
        try {
            PersistenceBuffer buffer = new PersistenceBuffer();
            buffer.setMimeType("application/json");
            JsonUtilities.objectMapper.writeValue(buffer.getOutputStream(), object);
            buffer.doneWriting();
            return buffer;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * @return a DataOutput that will automatically compress and buffer the output in memory for later consumption
     * to an InputStream, using little-endian number representations.
     */
    public DataOutput getDataOutput () {
        return littleEndianDataOutputStream;
    }

    /**
     * @return an OutputStream that will automatically compress and buffer the output in memory for later consumption
     * to an InputStream.
     */
    public OutputStream getOutputStream() {
        return outputStream;
    }

    /**
     * @return an input stream wrapping the internal byte buffer. Further writes are not possible after calling this.
     */
    public InputStream getInputStream () {
        if (!doneWriting) {
            throw new RuntimeException("You must mark a PersistenceBuffer 'doneWriting' before reading its contents as an InputStream.");
        }
        return buffer.getInputStream();
    }

    /**
     * @return the size of the underlying byte buffer. Because of compression this is not well-defined until writing is
     * completed. Therefore a call to this method will automatically end writing.
     */
    public long getSize() {
        if (!doneWriting) {
            throw new RuntimeException("You must mark a PersistenceBuffer 'doneWriting' before taking its size.");
        }
        return buffer.size();
    }

    /**
     * The buffer has a MIME type so it is possible to set metadata correctly when it is persisted to a web storage
     * location like AWS S3. This defaults to "application/octet-stream".
     */
    public void setMimeType(String mimeType) {
        this.mimeType = mimeType;
    }

    public String getMimeType() {
        return mimeType;
    }

    /**
     * Signal that writing to this buffer is complete. Certain operations will not be possible until writing is complete.
     */
    public void doneWriting() {
        if (doneWriting) {
            throw new RuntimeException("Persistence buffer marked doneWriting more than once.");
        }
        this.doneWriting = true;
        try {
            // Close the compressed stream rather than the underlying byte array stream to flush out compressed data.
            outputStream.close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

}
