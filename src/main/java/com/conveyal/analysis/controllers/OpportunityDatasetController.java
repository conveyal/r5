package com.conveyal.analysis.controllers;

import com.conveyal.analysis.AnalysisServerException;
import com.conveyal.analysis.UserPermissions;
import com.conveyal.analysis.components.TaskScheduler;
import com.conveyal.analysis.grids.SeamlessCensusGridExtractor;
import com.conveyal.analysis.models.OpportunityDataset;
import com.conveyal.analysis.models.Region;
import com.conveyal.analysis.models.SpatialDataSource;
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
import com.conveyal.r5.util.ProgressListener;
import com.google.common.io.Files;
import com.mongodb.QueryBuilder;
import org.apache.commons.fileupload.FileItem;
import org.apache.commons.fileupload.FileItemFactory;
import org.apache.commons.fileupload.FileUploadException;
import org.apache.commons.fileupload.disk.DiskFileItemFactory;
import org.apache.commons.fileupload.servlet.ServletFileUpload;
import org.apache.commons.io.FilenameUtils;
import org.bson.types.ObjectId;
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
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import static com.conveyal.analysis.datasource.SpatialLayers.detectUploadFormatAndValidate;
import static com.conveyal.analysis.util.JsonUtil.toJson;
import static com.conveyal.file.FileCategory.GRIDS;
import static com.conveyal.r5.analyst.WebMercatorGridPointSet.parseZoom;

/**
 * Controller that handles fetching opportunity datasets (grids and other pointset formats).
 */
public class OpportunityDatasetController implements HttpController {

    private static final Logger LOG = LoggerFactory.getLogger(OpportunityDatasetController.class);

    private static final FileItemFactory fileItemFactory = new DiskFileItemFactory();

    // Component Dependencies

    private final FileStorage fileStorage;
    private final TaskScheduler taskScheduler;
    private final SeamlessCensusGridExtractor extractor;

    public OpportunityDatasetController (
            FileStorage fileStorage,
            TaskScheduler taskScheduler,
            SeamlessCensusGridExtractor extractor
    ) {
        this.fileStorage = fileStorage;
        this.taskScheduler = taskScheduler;
        this.extractor = extractor;
    }

    /** Store upload status objects FIXME trivial Javadoc */
    private final List<OpportunityDatasetUploadStatus> uploadStatuses = new ArrayList<>();

    private JSONObject getJSONURL (FileStorageKey key) {
        JSONObject json = new JSONObject();
        String url = fileStorage.getURL(key);
        json.put("url", url);
        return json;
    }

    private void addStatusAndRemoveOldStatuses(OpportunityDatasetUploadStatus status) {
        uploadStatuses.add(status);
        LocalDateTime now = LocalDateTime.now();
        uploadStatuses.removeIf(s -> s.completedAt != null &&
                LocalDateTime.ofInstant(s.completedAt.toInstant(), ZoneId.systemDefault()).isBefore(now.minusDays(7))
        );
    }

    private Collection<OpportunityDataset> getRegionDatasets(Request req, Response res) {
        return Persistence.opportunityDatasets.findPermitted(
                QueryBuilder.start("regionId").is(req.params("regionId")).get(),
                UserPermissions.from(req)
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

    private List<OpportunityDatasetUploadStatus> getRegionUploadStatuses(Request req, Response res) {
        String regionId = req.params("regionId");
        return uploadStatuses
                .stream()
                .filter(status -> status.regionId.equals(regionId))
                .collect(Collectors.toList());
    }

    private boolean clearStatus(Request req, Response res) {
        String statusId = req.params("statusId");
        return uploadStatuses.removeIf(s -> s.id.equals(statusId));
    }

    private OpportunityDatasetUploadStatus downloadLODES(Request req, Response res) {
        final String regionId = req.params("regionId");
        final int zoom = parseZoom(req.queryParams("zoom"));
        final UserPermissions userPermissions = UserPermissions.from(req);
        final Region region = Persistence.regions.findByIdIfPermitted(regionId, userPermissions);
        // Common UUID for all LODES datasets created in this download (e.g. so they can be grouped together and
        // deleted as a batch using deleteSourceSet)
        // The bucket name contains the specific lodes data set and year so works as an appropriate name
        final OpportunityDatasetUploadStatus status = new OpportunityDatasetUploadStatus(regionId, extractor.sourceName);
        addStatusAndRemoveOldStatuses(status);

        SpatialDataSource source = new SpatialDataSource(userPermissions, extractor.sourceName);
        source.regionId = regionId;

        taskScheduler.enqueue(Task.create("Extracting LODES data")
                .forUser(userPermissions)
                .setHeavy(true)
                .withWorkProduct(source)
                .withAction((progressListener) -> {
                    try {
                        status.message = "Extracting census data for region";
                        List<Grid> grids = extractor.censusDataForBounds(region.bounds, zoom);
                        updateAndStoreDatasets(source, status, grids);
                    } catch (IOException e) {
                        status.completeWithError(e);
                        LOG.error("Exception processing LODES data: " + ExceptionUtils.stackTraceString(e));
                    }
                }));

        return status;
    }

    /**
     * Given a list of new PointSets, serialize each PointSet and save it to S3, then create a metadata object about
     * that PointSet and store it in Mongo.
     */
    private void updateAndStoreDatasets (SpatialDataSource source,
                                         OpportunityDatasetUploadStatus status,
                                         List<? extends PointSet> pointSets) {
        status.status = Status.UPLOADING;
        status.totalGrids = pointSets.size();
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
                status.uploadedGrids += 1;
                if (status.uploadedGrids == status.totalGrids) {
                    status.completeSuccessfully();
                }
                LOG.info("Completed {}/{} uploads for {}", status.uploadedGrids, status.totalGrids, status.name);
            } catch (NumberFormatException e) {
                throw new AnalysisServerException("Error attempting to parse number in uploaded file: " + e.toString());
            } catch (Exception e) {
                status.completeWithError(e);
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
     * This is a static utility method that should be reusable across different HttpControllers.
     */
    public static String getFormField(Map<String, List<FileItem>> formFields, String fieldName, boolean required) {
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
     * Handle many types of file upload. Returns a OpportunityDatasetUploadStatus which has a handle to request status.
     * The request should be a multipart/form-data POST request, containing uploaded files and associated parameters.
     */
    private OpportunityDatasetUploadStatus createOpportunityDataset(Request req, Response res) {
        final UserPermissions userPermissions = UserPermissions.from(req);
        final Map<String, List<FileItem>> formFields;
        try {
            ServletFileUpload sfu = new ServletFileUpload(fileItemFactory);
            formFields = sfu.parseParameterMap(req.raw());
        } catch (FileUploadException e) {
            // We can't even get enough information to create a status tracking object. Re-throw an exception.
            throw AnalysisServerException.fileUpload("Unable to parse opportunity dataset. " + ExceptionUtils.stackTraceString(e));
        }

        // Parse required fields. Will throw a ServerException on failure.
        final String sourceName = getFormField(formFields, "Name", true);
        final String regionId = getFormField(formFields, "regionId", true);
        final int zoom = parseZoom(getFormField(formFields, "zoom", false));

        // Create a region-wide status object tracking the processing of opportunity data.
        // Create the status object before doing anything including input and parameter validation, so that any problems
        // are recorded in a persistent purpose-built way rather than falling back on the UI's catch-all error window.
        // TODO more standardized mechanism for tracking asynchronous tasks and catching exceptions on them
        OpportunityDatasetUploadStatus status = new OpportunityDatasetUploadStatus(regionId, sourceName);
        addStatusAndRemoveOldStatuses(status);

        final List<FileItem> fileItems;
        final FileStorageFormat uploadFormat;
        final Map<String, String> parameters;
        try {
            // Validate inputs and parameters, which will throw an exception if there's anything wrong with them.
            // Call remove() rather than get() so that subsequent code will see only string parameters, not the files.
            fileItems = formFields.remove("files");
            uploadFormat = detectUploadFormatAndValidate(fileItems);
            parameters = extractStringParameters(formFields);
        } catch (Exception e) {
            status.completeWithError(e);
            return status;
        }

        // We are going to call several potentially slow blocking methods to create and persist new pointsets.
        // This whole series of actions will be run sequentially but within an asynchronous Executor task.
        // After enqueueing, the status is returned so the UI can track progress.
        taskScheduler.enqueueHeavyTask(() -> {
            try {
                // A place to accumulate all the PointSets created, both FreeForm and Grids.
                List<PointSet> pointsets = new ArrayList<>();
                if (uploadFormat == FileStorageFormat.GRID) {
                    LOG.info("Detected opportunity dataset stored in Conveyal binary format.");
                    pointsets.addAll(createGridsFromBinaryGridFiles(fileItems, status));
                } else if (uploadFormat == FileStorageFormat.SHP) {
                    LOG.info("Detected opportunity dataset stored as ESRI shapefile.");
                    pointsets.addAll(createGridsFromShapefile(fileItems, zoom, status));
                } else if (uploadFormat == FileStorageFormat.CSV) {
                    LOG.info("Detected opportunity dataset stored as CSV");
                    // Create a grid even when user has requested a freeform pointset so we have something to visualize.
                    FileItem csvFileItem = fileItems.get(0);
                    // FIXME why were we uploading to S3 using the file path not the UUID?
                    // writeFileToS3(csvFile);
                    // TODO report progress / status as with grids. That involves pre-scanning the CSV which would be
                    //      facilitated by retaining the CSV server side and later converting to pointset.
                    boolean requestedFreeForm = Boolean.parseBoolean(parameters.get("freeform"));
                    // Hack to enable freeform pointset building without exposing a UI element, via file name.
                    if (csvFileItem.getName().contains("FREEFORM_PS.")) {
                        requestedFreeForm = true;
                    }
                    if (requestedFreeForm) {
                        LOG.info("Processing CSV as freeform (rather than gridded) pointset as requested.");
                        // This newer process creates a FreeFormPointSet only for the specified count fields,
                        // as well as a Grid to assist in visualization of the uploaded data.
                        for (FreeFormPointSet freeForm : createFreeFormPointSetsFromCsv(csvFileItem, parameters)) {
                            Grid gridFromFreeForm = Grid.fromFreeForm(freeForm, zoom);
                            pointsets.add(freeForm);
                            pointsets.add(gridFromFreeForm);
                        }
                    } else {
                        // This is the common default process: create a grid for every non-ignored field in the CSV.
                        pointsets.addAll(createGridsFromCsv(csvFileItem, formFields, zoom, status));
                    }
                }
                if (pointsets.isEmpty()) {
                    throw new RuntimeException("No opportunity dataset was created from the files uploaded.");
                }
                LOG.info("Uploading opportunity datasets to S3 and storing metadata in database.");
                // Create a single unique ID string that will be referenced by all opportunity datasets produced by
                // this upload. This allows us to group together datasets from the same source and associate them with
                // the file(s) that produced them.
                SpatialDataSource source = new SpatialDataSource(userPermissions, sourceName);
                source.regionId = regionId;
                updateAndStoreDatasets(source, status, pointsets);
            } catch (Exception e) {
                e.printStackTrace();
                status.completeWithError(e);
            }
        });
        return status;
    }

    /**
     * Given pre-parsed multipart POST data containing some text fields, pull those fields out into a simple String
     * Map to simplify later use, performing some validation in the process.
     * All FileItems are expected to be form fields, not uploaded files, and all items should have only a single subitem
     * which can be understood as a UTF-8 String.
     */
    private Map<String, String> extractStringParameters(Map<String, List<FileItem>> formFields) {
        // All other keys should be for String parameters.
        Map<String, String> parameters = new HashMap<>();
        formFields.forEach((key, items) -> {
            if (items.size() != 1) {
                LOG.error("In multipart form upload, key '{}' had {} sub-items (expected one).", key, items.size());
            }
            FileItem fileItem = items.get(0);
            if (fileItem.isFormField()) {
                try {
                    parameters.put(key, fileItem.getString("UTF-8"));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            } else {
                LOG.warn("In multipart form upload, key '{}' was not for a form field.", key);
            }
        });
        return parameters;
    }

    private OpportunityDataset editOpportunityDataset(Request request, Response response) throws IOException {
        return Persistence.opportunityDatasets.updateFromJSONRequest(request);
    }

    private Collection<OpportunityDataset> deleteSourceSet(Request request, Response response) {
        String sourceId = request.params("sourceId");
        UserPermissions userPermissions = UserPermissions.from(request);
        Collection<OpportunityDataset> datasets = Persistence.opportunityDatasets.findPermitted(
                QueryBuilder.start("sourceId").is(sourceId).get(), userPermissions);
        datasets.forEach(dataset -> deleteDataset(dataset._id, userPermissions));
        return datasets;
    }

    private OpportunityDataset deleteOpportunityDataset(Request request, Response response) {
        String opportunityDatasetId = request.params("_id");
        return deleteDataset(opportunityDatasetId, UserPermissions.from(request));
    }

    /**
     * Delete an Opportunity Dataset from the database and all formats from the file store.
     */
    private OpportunityDataset deleteDataset(String id, UserPermissions userPermissions) {
        OpportunityDataset dataset = Persistence.opportunityDatasets.removeIfPermitted(id, userPermissions);
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
                                                 int zoom,
                                                 OpportunityDatasetUploadStatus status) throws Exception {

        String latField = getFormField(query, "latField", true);
        String lonField = getFormField(query, "lonField", true);
        String idField = getFormField(query, "idField", false);

        // Optional fields to run grid construction twice with two different sets of points.
        // This is only really useful when creating grids to visualize freeform pointsets for one-to-one analyses.
        String latField2 = getFormField(query, "latField2", false);
        String lonField2 = getFormField(query, "lonField2", false);

        List<String> ignoreFields = Arrays.asList(idField, latField2, lonField2);
        InputStreamProvider csvStreamProvider = new FileItemInputStreamProvider(csvFileItem);
        List<Grid> grids = Grid.fromCsv(csvStreamProvider, latField, lonField, ignoreFields, zoom, status);
        // TODO verify correctness of this second pass
        if (latField2 != null && lonField2 != null) {
            ignoreFields = Arrays.asList(idField, latField, lonField);
            grids.addAll(Grid.fromCsv(csvStreamProvider, latField2, lonField2, ignoreFields, zoom, status));
        }

        return grids;
    }

    /**
     * Create a grid from an input stream containing a binary grid file.
     * For those in the know, we can upload manually created binary grid files.
     */
    private List<Grid> createGridsFromBinaryGridFiles(List<FileItem> uploadedFiles,
                                                             OpportunityDatasetUploadStatus status) throws Exception {

        List<Grid> grids = new ArrayList<>();
        status.totalFeatures = uploadedFiles.size();
        for (FileItem fileItem : uploadedFiles) {
            Grid grid = Grid.read(fileItem.getInputStream());
            String name = fileItem.getName();
            // Remove ".grid" from the name
            if (name.contains(".grid")) name = name.split(".grid")[0];
            grid.name = name;
            grids.add(grid);
            status.completedFeatures += 1;
        }
        status.completedFeatures = status.totalFeatures;
        return grids;
    }

    /**
     * Preconditions: fileItems must contain SHP, DBF, and PRJ files, and optionally SHX. All files should have the
     * same base name, and should not contain any other files but these three or four.
     */
    private List<Grid> createGridsFromShapefile(List<FileItem> fileItems,
                                                       int zoom,
                                                       OpportunityDatasetUploadStatus status) throws Exception {

        // In the caller, we should have already verified that all files have the same base name and have an extension.
        // Extract the relevant files: .shp, .prj, .dbf, and .shx.
        // We need the SHX even though we're looping over every feature as they might be sparse.
        Map<String, FileItem> filesByExtension = new HashMap<>();
        for (FileItem fileItem : fileItems) {
            filesByExtension.put(FilenameUtils.getExtension(fileItem.getName()).toUpperCase(), fileItem);
        }

        // Copy the shapefile component files into a temporary directory with a fixed base name.
        File tempDir = Files.createTempDir();

        File shpFile = new File(tempDir, "grid.shp");
        filesByExtension.get("SHP").write(shpFile);

        File prjFile = new File(tempDir, "grid.prj");
        filesByExtension.get("PRJ").write(prjFile);

        File dbfFile = new File(tempDir, "grid.dbf");
        filesByExtension.get("DBF").write(dbfFile);

        // The .shx file is an index. It is optional, and not needed for dense shapefiles.
        if (filesByExtension.containsKey("SHX")) {
            File shxFile = new File(tempDir, "grid.shx");
            filesByExtension.get("SHX").write(shxFile);
        }

        List<Grid> grids = Grid.fromShapefile(shpFile, zoom, status);
        tempDir.delete();
        return grids;
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

    /**
     * Implements R5 ProgressListener interface to allow code in R5 to update it.
     * This is serialized into HTTP responses so all fields must be public.
     * TODO generalize into a system for tracking progress on all asynchronous server-side tasks.
     */
    public static class OpportunityDatasetUploadStatus implements ProgressListener {
        public String id;
        public int totalFeatures = 0;
        public int completedFeatures = 0;
        public int totalGrids = 0;
        public int uploadedGrids = 0;
        public String regionId;
        public Status status = Status.PROCESSING;
        public String name;
        public String message;
        public Date createdAt;
        public Date completedAt;

        OpportunityDatasetUploadStatus(String regionId, String name) {
            this.id = new ObjectId().toString();
            this.regionId = regionId;
            this.name = name;
            this.createdAt = new Date();
        }

        private void completed (Status status) {
            this.status = status;
            this.completedAt = new Date();
        }

        public void completeWithError (Exception e) {
            message = "Unable to create opportunity dataset. " + ExceptionUtils.stackTraceString(e);
            completed(Status.ERROR);
        }

        public void completeSuccessfully () {
            completed(Status.DONE);
        }

        @Override
        public void setTotalItems (int nTotal) {
            totalFeatures = nTotal;
        }

        @Override
        public void setCompletedItems (int nComplete) {
            completedFeatures = nComplete;
        }
    }

    private enum Status {
        UPLOADING, PROCESSING, ERROR, DONE
    }

    @Override
    public void registerEndpoints (spark.Service sparkService) {
        sparkService.path("/api/opportunities", () -> {
            sparkService.post("", this::createOpportunityDataset, toJson);
            sparkService.post("/region/:regionId/download", this::downloadLODES, toJson);
            sparkService.get("/region/:regionId/status", this::getRegionUploadStatuses, toJson);
            sparkService.delete("/region/:regionId/status/:statusId", this::clearStatus, toJson);
            sparkService.get("/region/:regionId", this::getRegionDatasets, toJson);
            sparkService.delete("/source/:sourceId", this::deleteSourceSet, toJson);
            sparkService.delete("/:_id", this::deleteOpportunityDataset, toJson);
            sparkService.get("/:_id", this::getOpportunityDataset, toJson);
            sparkService.put("/:_id", this::editOpportunityDataset, toJson);
            sparkService.get("/:_id/:format", this::downloadOpportunityDataset, toJson);
        });
    }
}
