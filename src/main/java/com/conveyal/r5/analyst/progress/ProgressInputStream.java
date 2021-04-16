package com.conveyal.r5.analyst.progress;

import org.apache.commons.fileupload.FileItem;
import org.apache.commons.io.input.ProxyInputStream;

import java.io.IOException;
import java.io.InputStream;

import static com.google.common.base.Preconditions.checkArgument;
import static org.apache.commons.io.IOUtils.EOF;

/**
 * This will report progress as the total number of bytes that have passed through the stream, like CountingInputStream.
 * This can exceed 100% of the file size if the caller uses mark and reset. The progressListener should be
 * pre-configured with the total number of bytes expected and a detail message using ProgressListener::beginTask.
 * The static method forFileItem() demonstrates usage when reading from a file of known length.
 */
public class ProgressInputStream extends ProxyInputStream {

    private final ProgressListener progressListener;

    public ProgressInputStream (ProgressListener progressListener, InputStream proxy) {
        super(proxy);
        this.progressListener = progressListener;
    }

    @Override
    protected synchronized void afterRead (final int n) {
        if (n != EOF) {
            progressListener.increment(n);
        }
    }

    @Override
    public synchronized long skip (final long length) throws IOException {
        final long skippedBytes = super.skip(length);
        progressListener.increment((int) skippedBytes);
        return skippedBytes;
    }

    /**
     * Given an uploaded file, report progress on reading it.
     * Incrementing the progress seems to introduce some inefficiency when performing unbuffered small reads, such as
     * calls to InputStream.read() which are used by DataInputStream to read numbers.
     * TODO wrap in buffered input stream to reduce small read calls, or tune to only report once per percentage?
     */
    public static ProgressInputStream forFileItem (FileItem fileItem, ProgressListener progressListener) {
        try {
            checkArgument(fileItem.getSize() < Integer.MAX_VALUE);
            progressListener.beginTask("Reading file " + fileItem.getName(), (int)(fileItem.getSize()));
            return new ProgressInputStream(progressListener, fileItem.getInputStream());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

}
