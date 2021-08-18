package com.conveyal.analysis.controllers;

import com.conveyal.analysis.AnalysisServerException;
import com.conveyal.analysis.UserPermissions;
import com.conveyal.analysis.components.TaskScheduler;
import com.conveyal.analysis.datasource.DataSourceIngester;
import com.conveyal.analysis.grids.SeamlessCensusGridExtractor;
import com.conveyal.analysis.models.DataSource;
import com.conveyal.analysis.models.SpatialDataSource;
import com.conveyal.analysis.persistence.AnalysisCollection;
import com.conveyal.analysis.persistence.AnalysisDB;
import com.conveyal.analysis.util.HttpUtils;
import com.conveyal.file.FileStorage;
import com.conveyal.file.FileStorageFormat;
import com.conveyal.file.FileStorageKey;
import com.conveyal.r5.analyst.progress.Task;
import com.conveyal.r5.analyst.progress.WorkProduct;
import org.apache.commons.fileupload.FileItem;
import org.apache.commons.fileupload.disk.DiskFileItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import spark.Request;
import spark.Response;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;

import static com.conveyal.analysis.controllers.OpportunityDatasetController.getFormField;
import static com.conveyal.analysis.datasource.SpatialLayers.detectUploadFormatAndValidate;
import static com.conveyal.analysis.util.JsonUtil.toJson;
import static com.conveyal.file.FileCategory.DATASOURCES;
import static com.conveyal.r5.analyst.WebMercatorGridPointSet.parseZoom;
import static com.conveyal.r5.analyst.progress.WorkProductType.DATA_SOURCE;
import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;

/**
 * Controller that handles CRUD of DataSources, which are Mongo metadata about user-uploaded files.
 * Unlike some Mongo documents, these are mostly created and updated by backend validation and processing methods.
 * Currently this handles only one subtype: SpatialDataSource, which represents GIS-like geospatial feature data.
 */
public class DataSourceController implements HttpController {

    private static final Logger LOG = LoggerFactory.getLogger(DataSourceController.class);

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
        this.dataSourceCollection = database.getAnalysisCollection("dataSources", DataSource.class);
    }

    private List<DataSource> getAllDataSourcesForRegion (Request req, Response res) {
        return dataSourceCollection.findPermitted(
                eq("regionId", req.params("regionId")), UserPermissions.from(req)
        );
    }

    private Object getOneDataSourceById (Request req, Response res) {
        return dataSourceCollection.findPermittedByRequestParamId(req, res);
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
                }));

        return source;
    }

    /**
     * A file is posted to this endpoint to create a new DataSource. It is validated and metadata are extracted.
     * The request should be a multipart/form-data POST request, containing uploaded files and associated parameters.
     * In a standard REST API, a post would return the ID of the newly created DataSource. Here we're starting an async
     * background process, so we return the task or its work product?
     */
    private WorkProduct handleUpload (Request req, Response res) {
        final UserPermissions userPermissions = UserPermissions.from(req);
        final Map<String, List<FileItem>> formFields = HttpUtils.getRequestFiles(req.raw());

        DataSourceIngester ingester = DataSourceIngester.forFormFields(
                fileStorage, dataSourceCollection, formFields, userPermissions
        );

        Task backgroundTask = Task.create("Processing uploaded files: " + ingester.getDataSourceName())
                .forUser(userPermissions)
                //.withWorkProduct(dataSource)
                // or should TaskActions have a method to return their work product?
                // Or a WorkProductDescriptor, with type, region, and ID?
                // TaskActions could define methods to return a title, workProductDescriptor, etc.
                // Then we just have taskScheduler.enqueue(Task.forAction(user, ingester));
                .withWorkProduct(DATA_SOURCE, ingester.getDataSourceId(), ingester.getRegionId())
                .withAction(ingester);

        taskScheduler.enqueue(backgroundTask);
        return backgroundTask.workProduct;
    }

    private Collection<DataSource> deleteOneDataSourceById (Request request, Response response) {
        DataSource source = dataSourceCollection.findPermittedByRequestParamId(request, response);
        // TODO delete files from storage
        // TODO delete referencing database records
        //      Shouldn't this be deleting by ID instead of sending the whole document?
        dataSourceCollection.delete(source);
        // TODO why do our delete methods return a list of documents? Can we just return the ID or HTTP status code?
        // Isn't this going to fail since the document was just deleted?
        return dataSourceCollection.findPermitted(
                eq("regionId", request.params("regionId")), UserPermissions.from(request)
        );
    }

    @Override
    public void registerEndpoints (spark.Service sparkService) {
        sparkService.path("/api/datasource", () -> {
            sparkService.get("/:_id", this::getOneDataSourceById, toJson);
            sparkService.get("/region/:regionId", this::getAllDataSourcesForRegion, toJson);
            sparkService.delete("/:_id", this::deleteOneDataSourceById, toJson);
            sparkService.post("", this::handleUpload, toJson);
            sparkService.post("/region/:regionId/download", this::downloadLODES, toJson);
        });
    }
}
