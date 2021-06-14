package com.conveyal.analysis.controllers;

import com.conveyal.analysis.AnalysisServerException;
import com.conveyal.analysis.UserPermissions;
import com.conveyal.analysis.components.TaskScheduler;
import com.conveyal.analysis.grids.SeamlessCensusGridExtractor;
import com.conveyal.analysis.models.OpportunityDataset;
import com.conveyal.analysis.models.Region;
import com.conveyal.analysis.models.SpatialDatasetSource;
import com.conveyal.analysis.persistence.AnalysisCollection;
import com.conveyal.analysis.persistence.AnalysisDB;
import com.conveyal.analysis.persistence.Persistence;
import com.conveyal.analysis.util.FileItemInputStreamProvider;
import com.conveyal.file.FileStorage;
import com.conveyal.file.FileStorageFormat;
import com.conveyal.file.FileStorageKey;
import com.conveyal.file.FileUtils;
import com.conveyal.r5.analyst.FreeFormPointSet;
import com.conveyal.r5.analyst.Grid;
import com.conveyal.r5.analyst.PointSet;
import com.conveyal.r5.analyst.progress.Task;
import com.conveyal.r5.util.ExceptionUtils;
import com.conveyal.r5.util.InputStreamProvider;
import com.mongodb.QueryBuilder;
import org.apache.commons.fileupload.FileItem;
import org.apache.commons.fileupload.FileItemFactory;
import org.apache.commons.fileupload.FileUploadException;
import org.apache.commons.fileupload.disk.DiskFileItem;
import org.apache.commons.fileupload.disk.DiskFileItemFactory;
import org.apache.commons.fileupload.servlet.ServletFileUpload;
import org.json.simple.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import spark.Request;
import spark.Response;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import static com.conveyal.analysis.components.HttpApi.USER_PERMISSIONS_ATTRIBUTE;
import static com.conveyal.analysis.spatial.SpatialDataset.SourceFormat;
import static com.conveyal.analysis.spatial.SpatialDataset.detectUploadFormatAndValidate;
import static com.conveyal.analysis.util.JsonUtil.toJson;
import static com.conveyal.file.FileCategory.GRIDS;
import static com.conveyal.file.FileCategory.RESOURCES;
import static com.conveyal.r5.analyst.WebMercatorGridPointSet.parseZoom;
import static com.conveyal.r5.analyst.progress.WorkProductType.SPATIAL_DATASET_SOURCE;

/**
 * Controller that handles fetching opportunity datasets (grids and other pointset formats).
 */
public class SpatialDatasetController implements HttpController {

    private static final Logger LOG = LoggerFactory.getLogger(SpatialDatasetController.class);

    private static final FileItemFactory fileItemFactory = new DiskFileItemFactory();

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

    private JSONObject getJSONURL (FileStorageKey key) {
        JSONObject json = new JSONObject();
        String url = fileStorage.getURL(key);
        json.put("url", url);
        return json;
    }

    private Collection<OpportunityDataset> getRegionDatasets(Request req, Response res) {
        return Persistence.opportunityDatasets.findPermitted(
                QueryBuilder.start("regionId").is(req.params("regionId")).get(),
                req.attribute("accessGroup")
        );
    }

    private Object getOpportunityDataset(Request req, Response res) {
        OpportunityDataset dataset = Persistence.opportunityDatasets.findByIdFromRequestIfPermitted(req);
        if (dataset.format == FileStorageFormat.GRID) {
            return getJSONURL(dataset.getStorageKey());
        } else {
            // Currently the UI can only visualize grids, not other kinds of datasets (freeform points).
            // We do generate a rasterized grid for each of the freeform pointsets we create, so ideally we'd redirect
            // to that grid for display and preview, but the freeform and corresponding grid pointset have different
            // IDs and there are no references between them.
            LOG.error("We cannot yet visualize freeform pointsets. Returning nothing to the UI.");
            return null;
        }
    }

    private SpatialDatasetSource downloadLODES(Request req, Response res) {
        final String regionId = req.params("regionId");
        final int zoom = parseZoom(req.queryParams("zoom"));

        UserPermissions userPermissions = req.attribute(USER_PERMISSIONS_ATTRIBUTE);
        final Region region = Persistence.regions.findByIdIfPermitted(regionId, userPermissions.accessGroup);
        // Common UUID for all LODES datasets created in this download (e.g. so they can be grouped together and
        // deleted as a batch using deleteSourceSet)
        // The bucket name contains the specific lodes data set and year so works as an appropriate name

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
     * Given a list of new PointSets, serialize each PointSet and save it to S3, then create a metadata object about
     * that PointSet and store it in Mongo.
     */
    private void updateAndStoreDatasets (SpatialDatasetSource source,
                                         List<? extends PointSet> pointSets) {

        // Create an OpportunityDataset holding some metadata about each PointSet (Grid or FreeForm).
        final List<OpportunityDataset> datasets = new ArrayList<>();
        for (PointSet pointSet : pointSets) {
            OpportunityDataset dataset = new OpportunityDataset();
            dataset.sourceName = source.name;
            dataset.sourceId = source._id.toString();
            dataset.createdBy = source.createdBy;
            dataset.accessGroup = source.accessGroup;
            dataset.regionId = source.regionId;
            dataset.name = pointSet.name;
            dataset.totalPoints = pointSet.featureCount();
            dataset.totalOpportunities = pointSet.sumTotalOpportunities();
            dataset.format = getFormatCode(pointSet);
            if (dataset.format == FileStorageFormat.FREEFORM) {
                dataset.name = String.join(" ", pointSet.name, "(freeform)");
            }
            dataset.setWebMercatorExtents(pointSet);
            // TODO make origin and destination pointsets reference each other and indicate they are suitable
            //      for one-to-one analyses

            // Store the PointSet metadata in Mongo and accumulate these objects into the method return list.
            Persistence.opportunityDatasets.create(dataset);
            datasets.add(dataset);

            // Persist a serialized representation of each PointSet (not the metadata) to S3 or other object storage.
            // TODO this should probably be pulled out to another method, and possibly called one frame up.
            //      Persisting the PointSets to S3 is a separate task than making metadata and storing in Mongo.
            try {
                if (pointSet instanceof Grid) {
                    File gridFile = FileUtils.createScratchFile("grid");

                    OutputStream fos = new GZIPOutputStream(new FileOutputStream(gridFile));
                    ((Grid)pointSet).write(fos);

                    fileStorage.moveIntoStorage(dataset.getStorageKey(FileStorageFormat.GRID), gridFile);
                } else if (pointSet instanceof FreeFormPointSet) {
                    // Upload serialized freeform pointset back to S3
                    FileStorageKey fileStorageKey = new FileStorageKey(GRIDS, source.regionId + "/" + dataset._id +
                            ".pointset");
                    File pointsetFile = FileUtils.createScratchFile("pointset");

                    OutputStream os = new GZIPOutputStream(new FileOutputStream(pointsetFile));
                    ((FreeFormPointSet)pointSet).write(os);

                    fileStorage.moveIntoStorage(fileStorageKey, pointsetFile);
                } else {
                    throw new IllegalArgumentException("Unrecognized PointSet type, cannot persist it.");
                }
                // TODO task tracking
            } catch (NumberFormatException e) {
                throw new AnalysisServerException("Error attempting to parse number in uploaded file: " + e.toString());
            } catch (Exception e) {
                throw AnalysisServerException.unknown(e);
            }
        }
    }

    private static FileStorageFormat getFormatCode (PointSet pointSet) {
        if (pointSet instanceof FreeFormPointSet) {
            return FileStorageFormat.FREEFORM;
        } else if (pointSet instanceof Grid) {
            return FileStorageFormat.GRID;
        } else {
            throw new RuntimeException("Unknown pointset type.");
        }
    }

    /**
     * Given a CSV file, converts each property (CSV column) into a freeform (non-gridded) pointset.
     *
     * The provided multipart form data must include latField and lonField. To indicate paired origins and destinations
     * (e.g. to use results from an origin-destination survey in a one-to-one regional analysis), the form data should
     * include the optional latField2 and lonField2 fields.
     *
     * This method executes in a blocking (synchronous) manner, but it can take a while so should be called within an
     * non-blocking asynchronous task.
     */
    private List<FreeFormPointSet> createFreeFormPointSetsFromCsv(FileItem csvFileItem, Map<String, String> params) {

        String latField = params.get("latField");
        String lonField = params.get("lonField");
        if (latField == null || lonField == null) {
            throw AnalysisServerException.fileUpload("You must specify a latitude and longitude column.");
        }

        // The name of the column containing a unique identifier for each row. May be missing (null).
        String idField = params.get("idField");

        // The name of the column containing the opportunity counts at each point. May be missing (null).
        String countField = params.get("countField");

        // Optional secondary latitude, longitude, and count fields.
        // This allows you to create two matched parallel pointsets of the same size with the same IDs.
        String latField2 = params.get("latField2");
        String lonField2 = params.get("lonField2");

        try {
            List<FreeFormPointSet> pointSets = new ArrayList<>();
            InputStreamProvider csvStreamProvider = new FileItemInputStreamProvider(csvFileItem);
            pointSets.add(FreeFormPointSet.fromCsv(csvStreamProvider, latField, lonField, idField, countField));
            // The second pair of lat and lon fields allow creating two matched pointsets from the same CSV.
            // This is used for one-to-one travel times between specific origins/destinations.
            if (latField2 != null && lonField2 != null) {
                pointSets.add(FreeFormPointSet.fromCsv(csvStreamProvider, latField2, lonField2, idField, countField));
            }
            return pointSets;
        } catch (Exception e) {
            throw AnalysisServerException.fileUpload("Could not convert CSV to Freeform PointSet: " + e.toString());
        }

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
        final Map<String, List<FileItem>> formFields;
        try {
            ServletFileUpload sfu = new ServletFileUpload(fileItemFactory);
            formFields = sfu.parseParameterMap(req.raw());
        } catch (FileUploadException e) {
            // We can't even get enough information to create a status tracking object. Re-throw an exception.
            throw AnalysisServerException.fileUpload("Unable to parse uploaded file(s). " + ExceptionUtils.stackTraceString(e));
        }

        // Parse required fields. Will throw a ServerException on failure.
        final String sourceName = getFormField(formFields, "Name", true);
        final String regionId = getFormField(formFields, "regionId", true);

        // Initialize model object
        SpatialDatasetSource source = SpatialDatasetSource.create(userPermissions, sourceName).withRegion(regionId);

        taskScheduler.enqueue(Task.create("Storing " + sourceName)
            .forUser(userPermissions)
            .withWorkProduct(SPATIAL_DATASET_SOURCE, source._id.toString(), regionId)
            .withAction(progressListener -> {

                // Loop through uploaded files, registering the extensions and writing to storage (with filenames that
                // correspond to the source id)
                List<File> files = new ArrayList<>();
                final List<FileItem> fileItems = formFields.remove("files");
                for (FileItem fileItem : fileItems) {
                    File file = ((DiskFileItem) fileItem).getStoreLocation();
                    String filename = file.getName();
                    String extension = filename.substring(filename.lastIndexOf(".") + 1).toUpperCase(Locale.ROOT);
                    FileStorageKey key = new FileStorageKey(RESOURCES, source._id.toString(), extension);
                    fileStorage.moveIntoStorage(key, file);
                    files.add(fileStorage.getFile(key));
                }

                progressListener.beginTask("Detecting format", 1);
                final SourceFormat uploadFormat;
                try {
                    // Validate inputs, which will throw an exception if there's anything wrong with them.
                    uploadFormat = detectUploadFormatAndValidate(fileItems);
                    LOG.info("Handling uploaded {} file", uploadFormat);
                } catch (Exception e) {
                    throw AnalysisServerException.fileUpload("Problem reading uploaded spatial files" + e.getMessage());
                }
                progressListener.beginTask("Validating files", 1);
                source.validateAndSetDetails(uploadFormat, files);
                spatialSourceCollection.insert(source);
            }));
        return source;
    }

    private OpportunityDataset editOpportunityDataset(Request request, Response response) throws IOException {
        return Persistence.opportunityDatasets.updateFromJSONRequest(request);
    }

    private Collection<OpportunityDataset> deleteSourceSet(Request request, Response response) {
        String sourceId = request.params("sourceId");
        String accessGroup = request.attribute("accessGroup");
        Collection<OpportunityDataset> datasets = Persistence.opportunityDatasets.findPermitted(
                QueryBuilder.start("sourceId").is(sourceId).get(), accessGroup);

        datasets.forEach(dataset -> deleteDataset(dataset._id, accessGroup));

        return datasets;
    }

    private OpportunityDataset deleteOpportunityDataset(Request request, Response response) {
        String opportunityDatasetId = request.params("_id");
        return deleteDataset(opportunityDatasetId, request.attribute("accessGroup"));
    }

    /**
     * Delete an Opportunity Dataset from the database and all formats from the file store.
     */
    private OpportunityDataset deleteDataset(String id, String accessGroup) {
        OpportunityDataset dataset = Persistence.opportunityDatasets.removeIfPermitted(id, accessGroup);

        if (dataset == null) {
            throw AnalysisServerException.notFound("Opportunity dataset could not be found.");
        } else {
            fileStorage.delete(dataset.getStorageKey(FileStorageFormat.GRID));
            fileStorage.delete(dataset.getStorageKey(FileStorageFormat.PNG));
            fileStorage.delete(dataset.getStorageKey(FileStorageFormat.TIFF));
        }

        return dataset;
    }

    /**
     * Create a grid from WGS 84 points in a CSV file.
     * The supplied CSV file will not be deleted - it may be used again to make another (freeform) pointset.
     * TODO explain latField2 usage
     * @return one or two Grids for each numeric column in the CSV input.
     */
    private List<Grid> createGridsFromCsv(FileItem csvFileItem,
                                                 Map<String, List<FileItem>> query,
                                                 int zoom) throws Exception {

        String latField = getFormField(query, "latField", true);
        String lonField = getFormField(query, "lonField", true);
        String idField = getFormField(query, "idField", false);

        // Optional fields to run grid construction twice with two different sets of points.
        // This is only really useful when creating grids to visualize freeform pointsets for one-to-one analyses.
        String latField2 = getFormField(query, "latField2", false);
        String lonField2 = getFormField(query, "lonField2", false);

        List<String> ignoreFields = Arrays.asList(idField, latField2, lonField2);
        InputStreamProvider csvStreamProvider = new FileItemInputStreamProvider(csvFileItem);
        List<Grid> grids = Grid.fromCsv(csvStreamProvider, latField, lonField, ignoreFields, zoom, null);
        // TODO verify correctness of this second pass
        if (latField2 != null && lonField2 != null) {
            ignoreFields = Arrays.asList(idField, latField, lonField);
            grids.addAll(Grid.fromCsv(csvStreamProvider, latField2, lonField2, ignoreFields, zoom, null));
        }

        return grids;
    }

    /**
     * Create a grid from an input stream containing a binary grid file.
     * For those in the know, we can upload manually created binary grid files.
     */
    private List<Grid> createGridsFromBinaryGridFiles(List<FileItem> uploadedFiles) throws Exception {

        List<Grid> grids = new ArrayList<>();
        // TODO task size with uploadedFiles.size();
        for (FileItem fileItem : uploadedFiles) {
            Grid grid = Grid.read(fileItem.getInputStream());
            String name = fileItem.getName();
            // Remove ".grid" from the name
            if (name.contains(".grid")) name = name.split(".grid")[0];
            grid.name = name;
            // TODO task progress
            grids.add(grid);
        }
        // TODO mark task complete
        return grids;
    }

    /**
     * Preconditions: fileItems must contain SHP, DBF, and PRJ files, and optionally SHX. All files should have the
     * same base name, and should not contain any other files but these three or four.
     */
    private void createGridsFromShapefile(List<FileItem> fileItems) throws Exception {
        // TODO implement rasterization methods
    }

    /**
     * Respond to a request with a redirect to a downloadable file.
     * @param req should specify regionId, opportunityDatasetId, and an available download format (.tiff or .grid)
     */
    private Object downloadOpportunityDataset (Request req, Response res) throws IOException {
        FileStorageFormat downloadFormat;
        try {
            downloadFormat = FileStorageFormat.valueOf(req.params("format").toUpperCase());
        } catch (IllegalArgumentException iae) {
            // This code handles the deprecated endpoint for retrieving opportunity datasets
            // get("/api/opportunities/:regionId/:gridKey") is the same signature as this endpoint.
            String regionId = req.params("_id");
            String gridKey = req.params("format");
            FileStorageKey storageKey = new FileStorageKey(GRIDS, String.format("%s/%s.grid", regionId, gridKey));
            return getJSONURL(storageKey);
        }

        if (FileStorageFormat.GRID.equals(downloadFormat)) return getOpportunityDataset(req, res);

        final OpportunityDataset opportunityDataset = Persistence.opportunityDatasets.findByIdFromRequestIfPermitted(req);

        FileStorageKey gridKey = opportunityDataset.getStorageKey(FileStorageFormat.GRID);
        FileStorageKey formatKey = opportunityDataset.getStorageKey(downloadFormat);

        // if this grid is not on S3 in the requested format, try to get the .grid format
        if (!fileStorage.exists(gridKey)) {
            throw AnalysisServerException.notFound("Requested grid does not exist.");
        }

        if (!fileStorage.exists(formatKey)) {
            // get the grid and convert it to the requested format
            File gridFile = fileStorage.getFile(gridKey);
            Grid grid = Grid.read(new GZIPInputStream(new FileInputStream(gridFile))); // closes input stream
            File localFile = FileUtils.createScratchFile(downloadFormat.toString());
            FileOutputStream fos = new FileOutputStream(localFile);

            if (FileStorageFormat.PNG.equals(downloadFormat)) {
                grid.writePng(fos);
            } else if (FileStorageFormat.TIFF.equals(downloadFormat)) {
                grid.writeGeotiff(fos);
            }

            fileStorage.moveIntoStorage(formatKey, localFile);
        }

        return getJSONURL(formatKey);
    }

    @Override
    public void registerEndpoints (spark.Service sparkService) {
        sparkService.path("/api/spatial", () -> {
            sparkService.post("", this::handleUpload, toJson);
            sparkService.post("/region/:regionId/download", this::downloadLODES, toJson);
            sparkService.get("/region/:regionId", this::getRegionDatasets, toJson);
            sparkService.delete("/source/:sourceId", this::deleteSourceSet, toJson);
            sparkService.delete("/:_id", this::deleteOpportunityDataset, toJson);
            sparkService.get("/:_id", this::getOpportunityDataset, toJson);
            sparkService.put("/:_id", this::editOpportunityDataset, toJson);
            sparkService.get("/:_id/:format", this::downloadOpportunityDataset, toJson);
        });
    }
}
