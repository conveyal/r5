package com.conveyal.analysis.controllers;

import com.conveyal.analysis.AnalysisServerException;
import com.conveyal.analysis.UserPermissions;
import com.conveyal.analysis.components.TaskScheduler;
import com.conveyal.analysis.grids.SeamlessCensusGridExtractor;
import com.conveyal.analysis.models.SpatialDatasetSource;
import com.conveyal.analysis.persistence.AnalysisCollection;
import com.conveyal.analysis.persistence.AnalysisDB;
import com.conveyal.analysis.util.HttpUtils;
import com.conveyal.file.FileStorage;
import com.conveyal.file.FileStorageFormat;
import com.conveyal.file.FileStorageKey;
import com.conveyal.r5.analyst.progress.Task;
import org.apache.commons.fileupload.FileItem;
import org.apache.commons.fileupload.FileItemFactory;
import org.apache.commons.fileupload.disk.DiskFileItem;
import org.apache.commons.fileupload.disk.DiskFileItemFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import spark.Request;
import spark.Response;

import java.io.File;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;

import static com.conveyal.analysis.components.HttpApi.USER_PERMISSIONS_ATTRIBUTE;
import static com.conveyal.analysis.spatial.SpatialDataset.detectUploadFormatAndValidate;
import static com.conveyal.analysis.util.JsonUtil.toJson;
import static com.conveyal.file.FileCategory.RESOURCES;
import static com.conveyal.r5.analyst.WebMercatorGridPointSet.parseZoom;
import static com.conveyal.r5.analyst.progress.WorkProductType.RESOURCE;
import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;

/**
 * Controller that handles fetching opportunity datasets (grids and other pointset formats).
 */
public class SpatialDatasetController implements HttpController {

    private static final Logger LOG = LoggerFactory.getLogger(SpatialDatasetController.class);

    // Component Dependencies
    private final FileStorage fileStorage;
    private final AnalysisCollection<SpatialDatasetSource> spatialSourceCollection;
    private final TaskScheduler taskScheduler;
    private final SeamlessCensusGridExtractor extractor;

    public SpatialDatasetController (
            FileStorage fileStorage,
            AnalysisDB database,
            TaskScheduler taskScheduler,
            SeamlessCensusGridExtractor extractor
    ) {
        this.fileStorage = fileStorage;
        this.spatialSourceCollection = database.getAnalysisCollection("spatialSources", SpatialDatasetSource.class);
        this.taskScheduler = taskScheduler;
        this.extractor = extractor;
    }

    private List<SpatialDatasetSource> getRegionDatasets(Request req, Response res) {
        return spatialSourceCollection.findPermitted(
                and(eq("regionId", req.params("regionId"))),
                req.attribute("accessGroup")
        );
    }

    private Object getSource(Request req, Response res) {
        return spatialSourceCollection.findPermittedByRequestParamId(req, res);
    }

    private SpatialDatasetSource downloadLODES(Request req, Response res) {
        final String regionId = req.params("regionId");
        final int zoom = parseZoom(req.queryParams("zoom"));
        UserPermissions userPermissions = req.attribute(USER_PERMISSIONS_ATTRIBUTE);
        SpatialDatasetSource source = SpatialDatasetSource.create(userPermissions, extractor.sourceName)
                .withRegion(regionId);

        taskScheduler.enqueue(Task.create("Extracting LODES data")
                .forUser(userPermissions)
                .setHeavy(true)
                .withWorkProduct(source)
                .withAction((progressListener) -> {
                    // TODO implement
                }));

        return source;
    }

    /**
     * Get the specified field from a map representing a multipart/form-data POST request, as a UTF-8 String.
     * FileItems represent any form item that was received within a multipart/form-data POST request, not just files.
     */
    private String getFormField(Map<String, List<FileItem>> formFields, String fieldName, boolean required) {
        try {
            List<FileItem> fileItems = formFields.get(fieldName);
            if (fileItems == null || fileItems.isEmpty()) {
                if (required) {
                    throw AnalysisServerException.badRequest("Missing required field: " + fieldName);
                } else {
                    return null;
                }
            }
            String value = fileItems.get(0).getString("UTF-8");
            return value;
        } catch (UnsupportedEncodingException e) {
            throw AnalysisServerException.badRequest(String.format("Multipart form field '%s' had unsupported encoding",
                    fieldName));
        }
    }

    /**
     * Handle many types of spatial upload.
     * The request should be a multipart/form-data POST request, containing uploaded files and associated parameters.
     */
    private SpatialDatasetSource handleUpload(Request req, Response res) {
        final UserPermissions userPermissions = req.attribute(USER_PERMISSIONS_ATTRIBUTE);
        final Map<String, List<FileItem>> formFields = HttpUtils.getRequestFiles(req.raw());

        // Parse required fields. Will throw a ServerException on failure.
        final String sourceName = getFormField(formFields, "sourceName", true);
        final String regionId = getFormField(formFields, "regionId", true);

        // Initialize model object
        SpatialDatasetSource source = SpatialDatasetSource.create(userPermissions, sourceName).withRegion(regionId);

        taskScheduler.enqueue(Task.create("Uploading spatial dataset: " + sourceName)
            .forUser(userPermissions)
            .withWorkProduct(RESOURCE, source._id.toString(), regionId)
            .withAction(progressListener -> {

                // Loop through uploaded files, registering the extensions and writing to storage (with filenames that
                // correspond to the source id)
                List<File> files = new ArrayList<>();
                StringJoiner fileNames = new StringJoiner(", ");
                final List<FileItem> fileItems = formFields.get("sourceFiles");
                for (FileItem fileItem : fileItems) {
                    DiskFileItem dfi = (DiskFileItem) fileItem;
                    String filename = fileItem.getName();
                    fileNames.add(filename);
                    String extension = filename.substring(filename.lastIndexOf(".") + 1).toUpperCase(Locale.ROOT);
                    FileStorageKey key = new FileStorageKey(RESOURCES, source._id.toString(), extension);
                    fileStorage.moveIntoStorage(key, dfi.getStoreLocation());
                    files.add(fileStorage.getFile(key));
                }

                progressListener.beginTask("Detecting format", 1);
                final FileStorageFormat uploadFormat;
                try {
                    // Validate inputs, which will throw an exception if there's anything wrong with them.
                    uploadFormat = detectUploadFormatAndValidate(fileItems);
                    LOG.info("Handling uploaded {} file", uploadFormat);
                } catch (Exception e) {
                    throw AnalysisServerException.fileUpload("Problem reading uploaded spatial files" + e.getMessage());
                }
                progressListener.beginTask("Validating files", 1);
                source.description = "From uploaded files: " + fileNames;
                source.validateAndSetDetails(uploadFormat, files);
                spatialSourceCollection.insert(source);
            }));
        return source;
    }

    private Collection<SpatialDatasetSource> deleteSourceSet(Request request, Response response) {
        SpatialDatasetSource source = spatialSourceCollection.findPermittedByRequestParamId(request, response);
        // TODO delete files from storage
        // TODO delete referencing database records
        spatialSourceCollection.delete(source);

        return spatialSourceCollection.findPermitted(
                and(eq("regionId", request.params("regionId"))),
                request.attribute("accessGroup")
        );
    }

    @Override
    public void registerEndpoints (spark.Service sparkService) {
        sparkService.path("/api/spatial", () -> {
            sparkService.post("", this::handleUpload, toJson);
            sparkService.post("/region/:regionId/download", this::downloadLODES, toJson);
            sparkService.get("/region/:regionId", this::getRegionDatasets, toJson);
            sparkService.delete("/source/:_id", this::deleteSourceSet, toJson);
            sparkService.get("/:_id", this::getSource, toJson);
        });
    }
}
