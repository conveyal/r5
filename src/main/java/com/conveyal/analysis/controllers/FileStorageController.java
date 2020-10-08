package com.conveyal.analysis.controllers;

import com.conveyal.analysis.models.FileInfo;
import com.conveyal.analysis.persistence.AnalysisCollection;
import com.conveyal.analysis.persistence.AnalysisDB;
import com.conveyal.file.FileStorage;
import com.conveyal.file.FileUtils;
import spark.Request;
import spark.Response;
import spark.Service;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import static com.conveyal.analysis.util.JsonUtil.toJson;
import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;

/**
 * HTTP request handler methods allowing users to upload/download files from FileStorage implementations and CRUDing
 * metadata about those files in the database.
 */
public class FileStorageController implements HttpController {

    // Component dependencies
    private final FileStorage fileStorage;
    private final AnalysisDB database;

    // Internal fields
    private final AnalysisCollection<FileInfo> fileCollection;

    public FileStorageController (FileStorage fileStorage, AnalysisDB database) {
        this.fileStorage = fileStorage;
        this.database = database;

        // Get the collection for FileInfo (metadata about uploaded files).
        this.fileCollection = database.getAnalysisCollection("files", FileInfo.class);
    }

    @Override
    public void registerEndpoints (Service sparkService) {
        sparkService.path("/api/files", () -> {
            sparkService.get("", this::findAllForRegion, toJson);
            sparkService.post("", this::createFileInfo, toJson);
            sparkService.path("/:_id", () -> {
                sparkService.get("", (req, res) -> this.fileCollection.findById(req.params("_id")), toJson);
                sparkService.put("", this.fileCollection::update, toJson);
                sparkService.delete("", this::deleteFile);
                sparkService.get("/download-url", this::generateDownloadURL);
                sparkService.get("/download", this::downloadFile);
                sparkService.post("/upload", this::uploadFile, toJson);
            });
        });
    }

    /**
     * Find all associated FileInfo records for a region.
     */
    private List<FileInfo> findAllForRegion(Request req, Response res) {
        return fileCollection.findPermitted(and(eq("regionId", req.queryParams("regionId"))), req.attribute("accessGroup"));
    }

    /**
     * Create the metadata object used to represent a file in FileStorage. Note: this does not handle the process of
     * storing the file itself. See `addFile` for that.
     */
    private FileInfo createFileInfo(Request req, Response res) throws IOException {
        FileInfo fileInfo = fileCollection.create(req, res);
        fileInfo.path = FileInfo.generatePath(fileInfo.accessGroup, fileInfo._id, fileInfo.name);
        fileCollection.update(fileInfo);
        return fileInfo;
    }

    /**
     * Remove the FileInfo record from the database and the file from the FileStorage.
     */
    private boolean deleteFile(Request req, Response res) {
        FileInfo file = fileCollection.findPermittedByRequestParamId(req, res);
        fileStorage.delete(file.getKey());
        return fileCollection.delete(file).wasAcknowledged();
    }

    /**
     * Find FileInfo from passed in _id and generate a download URL corresponding to an accessible location of that
     * file.
     */
    private String generateDownloadURL(Request req, Response res) {
        FileInfo file = fileCollection.findPermittedByRequestParamId(req, res);
        res.type("text");
        return fileStorage.getURL(file.getKey());
    }

    /**
     * Find FileInfo by passing in and _id and download the corresponding file by returning an InputStream.
     */
    private InputStream downloadFile(Request req, Response res) throws IOException {
        FileInfo fileInfo = fileCollection.findPermittedByRequestParamId(req, res);
        File file = fileStorage.getFile(fileInfo.getKey());
        res.type(fileInfo.format.mimeType);
        if (FileUtils.isGzip(file)) {
            res.header("Content-Encoding", "gzip");
        }
        return FileUtils.getInputStream(file);
    }

    /**
     * Upload a file to the file storage. Requires a corresponding FileInfo representing the metadata and location of that
     * file.
     */
    private FileInfo uploadFile(Request req, Response res) throws Exception {
        FileInfo fileInfo = fileCollection.findPermittedByRequestParamId(req, res);
        File file = FileUtils.createScratchFile(req.raw().getInputStream());
        fileStorage.moveIntoStorage(fileInfo.getKey(), file);

        // Set status to ready
        fileInfo.isReady = true;
        fileInfo.updatedBy = req.attribute("email");

        // Store changes to the file info
        fileCollection.update(fileInfo);

        return fileInfo;
    }
}
