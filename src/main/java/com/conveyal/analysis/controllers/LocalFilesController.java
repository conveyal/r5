package com.conveyal.analysis.controllers;

import com.conveyal.file.FileCategory;
import com.conveyal.file.FileStorage;
import com.conveyal.file.FileStorageFormat;
import com.conveyal.file.FileStorageKey;
import com.conveyal.file.FileUtils;
import com.conveyal.file.LocalFileStorage;
import spark.Request;
import spark.Response;
import spark.Service;

import java.io.File;
import java.io.InputStream;
import java.util.Locale;

/**
 * Expose all files in storage while in offline mode.
 * Not done with Spark's built-in static file serving because that automatically gzips our already gzipped files.
 * Another good reason to eventually code directly against an HTTP server instead of using this framework.
 */
public class LocalFilesController implements HttpController {

    private final LocalFileStorage fileStorage;

    public LocalFilesController (FileStorage fileStorage) {
        this.fileStorage = (LocalFileStorage) fileStorage;
    }

    private Object getFile (Request req, Response res) throws Exception {
        String filename = req.splat()[0];
        FileCategory category = FileCategory.valueOf(req.params("category").toUpperCase(Locale.ROOT));
        FileStorageKey key = new FileStorageKey(category, filename);
        File file = fileStorage.getFile(key);
        FileStorageFormat format = FileStorageFormat.fromFilename(filename);
        if (format != null) {
            res.type(format.mimeType);
        }

        // If the content-encoding is set to gzip, Spark automatically gzips the response. This double-gzips anything
        // that was already gzipped. Some of our files are already gzipped, and we rely on the the client browser to
        // decompress them upon receiving them. Therefore, when serving a file that's already gzipped we bypass Spark,
        // piping it directly to the raw Jetty OutputStream. As soon as transferFromFileTo completes it closes the
        // output stream, which completes the HTTP response to the client. We must then return something to Spark. We
        // can't return null because Spark will spew errors about the endpoint being "not mapped" and try to replace
        // the response with a 404, so we return an empty String.
        res.header("Content-Encoding", "gzip");
        if (FileUtils.isGzip(file)) {
            // TODO Trace in debug: how does this actually work?
            //      Verify what this is transferring into - a buffer? In another reading thread?
            //      Is Jetty ServletOutputStream implementation automatically threaded or buffered?
            //      It appears to be buffered because the response has a Content-Length header.
            FileUtils.transferFromFileTo(file, res.raw().getOutputStream());
            // Despite writing to output stream, non-null return value required: https://stackoverflow.com/a/32794875
            return "";
        } else {
            return FileUtils.getInputStream(file);
        }
    }

    @Override
    public void registerEndpoints (Service sparkService) {
        sparkService.get("/files/:category/*", this::getFile);
    }

}
