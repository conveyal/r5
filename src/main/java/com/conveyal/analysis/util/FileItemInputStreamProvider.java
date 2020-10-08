package com.conveyal.analysis.util;

import com.conveyal.r5.util.InputStreamProvider;
import org.apache.commons.fileupload.FileItem;
import org.apache.commons.io.input.BOMInputStream;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * A way for Analysis Backend to repeatedly fetch the same InputStream from the FileItem class
 * (which its dependency R5 doesn't import). This automatically wraps the stream to buffer it
 * because the original stream may be an unbuffered FileInputStream. We also wrap the stream to
 * strip out any UTF byte-order mark. Although UTF-8 encoded files do not need a byte order mark and
 * it is not recommended, Windows text editors often add one anyway.
 *
 * This class is a way of repeatedly reading files that were uploaded as part of a multipart POST
 * form. Saving those to a temp file may end up just copying one temp file to another since we're
 * using DiskFileItemFactory. The uploaded files may already be on disk but they may also be cached
 * in memory. Ideally we'd just read directly from the FileItem's inputStream and not worry about
 * whether it was coming from a file at all. But interpreting the content sometimes requires reading
 * the stream twice. FileInputStreams can't be rewound, and R5 (where the streams are read) doesn't
 * know about FileItems, so it can't repeatedly request a new input stream without this
 * abstraction.
 */
public class FileItemInputStreamProvider implements InputStreamProvider {

    private FileItem fileItem;

    public FileItemInputStreamProvider (FileItem fileItem) {
        this.fileItem = fileItem;
    }

    @Override
    public InputStream getInputStream () throws IOException {
        return new BOMInputStream(new BufferedInputStream(fileItem.getInputStream()));
    }

}
