package com.conveyal.analysis.controllers;

import com.conveyal.file.FileCategory;
import com.conveyal.file.FileStorage;
import com.conveyal.file.FileStorageFormat;
import com.conveyal.file.FileStorageKey;
import com.conveyal.file.FileUtils;
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

    // Something feels whack here, this should more specifically be a LocalFileStorage
    private final FileStorage fileStorage;

    public LocalFilesController (FileStorage fileStorage) {
        this.fileStorage = fileStorage;
    }

    private InputStream getFile (Request req, Response res) throws Exception {
        String filename = req.splat()[0];
        FileCategory category = FileCategory.valueOf(req.params("category").toUpperCase(Locale.ROOT));
        FileStorageKey key = new FileStorageKey(category, filename);
        File file = fileStorage.getFile(key);
        FileStorageFormat format = FileStorageFormat.fromFilename(filename);
        res.type(format.mimeType);

        // If the content-encoding is set to gzip, Spark automatically gzips the response. This mangles data
        // that was already gzipped. Therefore, check if it's gzipped and pipe directly to the raw OutputStream.
        res.header("Content-Encoding", "gzip");
        if (FileUtils.isGzip(file)) {
            // TODO Trace in debug: how does this actually work?
            //      Verify what this is transferring into - a buffer? In another reading thread?
            //      Is Jetty ServletOutputStream implementation automatically threaded or buffered?
            FileUtils.transferFromFileTo(file, res.raw().getOutputStream());
            return null;
        } else {
            return FileUtils.getInputStream(file);
        }
    }

    @Override
    public void registerEndpoints (Service sparkService) {
        sparkService.get("/files/:category/*", this::getFile);
    }

}
