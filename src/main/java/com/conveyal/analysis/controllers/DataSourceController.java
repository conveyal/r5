package com.conveyal.analysis.controllers;

import com.conveyal.analysis.UserPermissions;
import com.conveyal.analysis.components.TaskScheduler;
import com.conveyal.analysis.datasource.DataSourcePreviewGenerator;
import com.conveyal.analysis.datasource.DataSourceUploadAction;
import com.conveyal.analysis.grids.SeamlessCensusGridExtractor;
import com.conveyal.analysis.models.DataSource;
import com.conveyal.analysis.models.GtfsDataSource;
import com.conveyal.analysis.models.OsmDataSource;
import com.conveyal.analysis.models.SpatialDataSource;
import com.conveyal.analysis.persistence.AnalysisCollection;
import com.conveyal.analysis.persistence.AnalysisDB;
import com.conveyal.analysis.util.HttpUtils;
import com.conveyal.file.FileStorage;
import com.conveyal.file.FileStorageFormat;
import com.conveyal.file.FileStorageKey;
import com.conveyal.r5.analyst.progress.Task;
import com.mongodb.client.result.DeleteResult;
import org.apache.commons.fileupload.FileItem;
import org.geotools.data.geojson.GeoJSONWriter;
import org.opengis.feature.simple.SimpleFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import spark.Request;
import spark.Response;

import java.io.File;
import java.io.OutputStream;
import java.lang.invoke.MethodHandles;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import static com.conveyal.analysis.util.JsonUtil.toJson;
import static com.conveyal.file.FileCategory.DATASOURCES;
import static com.conveyal.file.FileStorageFormat.SHP;
import static com.conveyal.r5.analyst.WebMercatorExtents.parseZoom;
import static com.mongodb.client.model.Filters.eq;

/**
 * Controller that handles CRUD of DataSources, which are Mongo metadata about user-uploaded files.
 * Unlike some Mongo documents, these are mostly created and updated by backend validation and processing methods.
 * Currently this handles only one subtype: SpatialDataSource, which represents GIS-like vector geospatial data.
 */
public class DataSourceController implements HttpController {

    private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    // Component Dependencies
    private final FileStorage fileStorage;
    private final TaskScheduler taskScheduler;
    private final SeamlessCensusGridExtractor extractor;

    // Collection in the database holding all our DataSources, which can be of several subtypes.
    private final AnalysisCollection<DataSource> dataSourceCollection;

    public DataSourceController (
            FileStorage fileStorage,
            AnalysisDB database,
            TaskScheduler taskScheduler,
            SeamlessCensusGridExtractor extractor
    ) {
        this.fileStorage = fileStorage;
        this.taskScheduler = taskScheduler;
        this.extractor = extractor;
        // We don't hold on to the AnalysisDB Component, just get one collection from it.
        // Register all the subclasses so the Mongo driver will recognize their discriminators.
        // TODO should this be done once in AnalysisDB and the collection reused everywhere? Is that threadsafe?
        this.dataSourceCollection = database.getAnalysisCollection(
            "dataSources", DataSource.class, SpatialDataSource.class, OsmDataSource.class, GtfsDataSource.class
        );
    }

    /** HTTP GET: Retrieve all DataSource records, filtered by the (required) regionId query parameter. */
    private List<DataSource> getAllDataSourcesForRegion (Request req, Response res) {
        return dataSourceCollection.findPermitted(
                eq("regionId", req.queryParams("regionId")), UserPermissions.from(req)
        );
    }

    /** HTTP GET: Retrieve a single DataSource record by the ID supplied in the URL path parameter. */
    private DataSource getOneDataSourceById (Request req, Response res) {
        return dataSourceCollection.findPermittedByRequestParamId(req);
    }

    /** HTTP DELETE: Delete a single DataSource record and associated files in FileStorage by supplied ID parameter. */
    private String deleteOneDataSourceById (Request request, Response response) {
        DataSource dataSource = dataSourceCollection.findPermittedByRequestParamId(request);
        DeleteResult deleteResult = dataSourceCollection.delete(dataSource);
        long nDeleted = deleteResult.getDeletedCount();
        // This will not delete the file if its extension when uploaded did not match the canonical one.
        // Ideally we should normalize file extensions when uploaded, but it's a little tricky to handle SHP sidecars.
        fileStorage.delete(dataSource.fileStorageKey());
        // This is so ad-hoc but it's not necessarily worth generalizing since SHP is the only format with sidecars.
        if (dataSource.fileFormat == SHP) {
            fileStorage.delete(new FileStorageKey(DATASOURCES, dataSource._id.toString(), "shx"));
            fileStorage.delete(new FileStorageKey(DATASOURCES, dataSource._id.toString(), "dbf"));
            fileStorage.delete(new FileStorageKey(DATASOURCES, dataSource._id.toString(), "prj"));
        }
        return "Deleted " + nDeleted;
    }

    static final long MAX_GEOJSON_SIZE = 4 * 1024 * 1024; // 4 MB

    /**
     * Produces GeoJSON representing the data source on a map. For preview purposes only, may not contain all features
     * in the data set and may exaggerate or distort some boundaries due to differences in coordinate system.
     */
    private Map<String, Object> getDataSourcePreview (Request request, Response response) {
        DataSource dataSource = getOneDataSourceById(request, response);
        try {
            // Special case for small GeoJSON files: return the whole file as its preview.
            // Use try-with-resources to auto-close the output stream when done.
            // Otherwise Spark will append the text "null" when it sees the null return value, creating invalid JSON.
            if (dataSource.fileFormat == FileStorageFormat.GEOJSON) {
                File file = fileStorage.getFile(dataSource.fileStorageKey());
                if (file.length() < MAX_GEOJSON_SIZE) {
                    try (OutputStream out = response.raw().getOutputStream()) {
                        Files.copy(file.toPath(), response.raw().getOutputStream());
                    }
                    return null;
                }
            }
            final var generator = new DataSourcePreviewGenerator(fileStorage);
            GeoJSONWriter gjw = new GeoJSONWriter(response.raw().getOutputStream());
            // GeoJsonWriter automatically reprojects to WGS84 from the source CRS.
            // Known problem: our wgs84Stream transforms coordinates but does not change the CRS, so the features
            // are double-projected upon writing to GeoJSON. All sources of features used here should provide accurate
            // CRS on the features, or none at all if they're already in WGS84.
            for (SimpleFeature feature : generator.previewFeatures(dataSource)) {
                gjw.write(feature);
            }
            gjw.close();
        } catch (Exception e) {
            throw new RuntimeException("Failed to write GeoJSON DataSource preview:", e);
        }
        return null;
    }

    private SpatialDataSource downloadLODES(Request req, Response res) {
        final String regionId = req.params("regionId");
        final int zoom = parseZoom(req.queryParams("zoom"));
        UserPermissions userPermissions = UserPermissions.from(req);
        SpatialDataSource source = new SpatialDataSource(userPermissions, extractor.sourceName);
        source.regionId = regionId;

        taskScheduler.enqueue(Task.create("Extracting LODES data")
                .forUser(userPermissions)
                .setHeavy(true)
                .withWorkProduct(source)
                .withAction((progressListener) -> {
                    // TODO implement
                    throw new UnsupportedOperationException();
                }));

        return source;
    }

    /**
     * A file is posted to this endpoint to create a new DataSource. It is validated and metadata are extracted.
     * The request should be a multipart/form-data POST request, containing uploaded files and associated parameters.
     * In standard REST API style, a POST would return the ID of the newly created DataSource. Here we're starting an
     * async background process, so we return the ID of the enqueued Task (rather than its work product, the DataSource).
     */
    private String handleUpload (Request req, Response res) {
        final UserPermissions userPermissions = UserPermissions.from(req);
        final Map<String, List<FileItem>> formFields = HttpUtils.getRequestFiles(req.raw());
        DataSourceUploadAction uploadAction = DataSourceUploadAction.forFormFields(
                fileStorage, dataSourceCollection, formFields, userPermissions
        );
        Task backgroundTask = Task.create("Processing uploaded files: " + uploadAction.getDataSourceName())
                .forUser(userPermissions)
                .withAction(uploadAction);

        taskScheduler.enqueue(backgroundTask);
        return backgroundTask.id.toString();
    }

    @Override
    public void registerEndpoints (spark.Service sparkService) {
        sparkService.path("/api/dataSource", () -> {
            sparkService.get("/", this::getAllDataSourcesForRegion, toJson);
            sparkService.get("/:_id", this::getOneDataSourceById, toJson);
            sparkService.delete("/:_id", this::deleteOneDataSourceById, toJson);
            sparkService.get("/:_id/preview", this::getDataSourcePreview, toJson);
            sparkService.post("", this::handleUpload, toJson);
            // regionId will be in query parameter
            sparkService.post("/addLodesDataSource", this::downloadLODES, toJson);
        });
    }

}
