package com.conveyal.analysis.controllers;

import com.conveyal.analysis.AnalysisServerException;
import com.conveyal.analysis.SelectingGridReducer;
import com.conveyal.analysis.UserPermissions;
import com.conveyal.analysis.components.broker.Broker;
import com.conveyal.analysis.components.broker.JobStatus;
import com.conveyal.analysis.models.AnalysisRequest;
import com.conveyal.analysis.models.OpportunityDataset;
import com.conveyal.analysis.models.RegionalAnalysis;
import com.conveyal.analysis.persistence.Persistence;
import com.conveyal.analysis.results.CsvResultType;
import com.conveyal.analysis.util.JsonUtil;
import com.conveyal.file.FileStorage;
import com.conveyal.file.FileStorageFormat;
import com.conveyal.file.FileStorageKey;
import com.conveyal.file.FileUtils;
import com.conveyal.r5.analyst.FreeFormPointSet;
import com.conveyal.r5.analyst.Grid;
import com.conveyal.r5.analyst.PointSet;
import com.conveyal.r5.analyst.PointSetCache;
import com.conveyal.r5.analyst.cluster.RegionalTask;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.primitives.Ints;
import com.mongodb.QueryBuilder;
import gnu.trove.list.array.TIntArrayList;
import org.mongojack.DBProjection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import spark.Request;
import spark.Response;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.zip.GZIPOutputStream;

import static com.conveyal.analysis.util.JsonUtil.toJson;
import static com.conveyal.file.FileCategory.BUNDLES;
import static com.conveyal.file.FileCategory.RESULTS;
import static com.conveyal.r5.transit.TransportNetworkCache.getScenarioFilename;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

/**
 * Spark HTTP handler methods that allow launching new regional analyses, as well as deleting them and fetching
 * information about them.
 */
public class RegionalAnalysisController implements HttpController {

    /** Until regional analysis config supplies percentiles in the request, hard-wire to our standard five. */
    private static final int[] DEFAULT_REGIONAL_PERCENTILES = new int[] {5, 25, 50, 75, 95};

    /**
     * Until the UI supplies cutoffs in the AnalysisRequest, hard-wire cutoffs.
     * The highest one is half our absolute upper limit of 120 minutes, which should by default save compute time.
     */
    public static final int[] DEFAULT_CUTOFFS = new int[] {5, 10, 15, 20, 25, 30, 35, 40, 45, 50, 55, 60};

    private static final Logger LOG = LoggerFactory.getLogger(RegionalAnalysisController.class);

    private final Broker broker;
    private final FileStorage fileStorage;

    public RegionalAnalysisController (Broker broker, FileStorage fileStorage) {
        this.broker = broker;
        this.fileStorage = fileStorage;
    }

    private Collection<RegionalAnalysis> getRegionalAnalysesForRegion(String regionId, UserPermissions userPermissions) {
        return Persistence.regionalAnalyses.findPermitted(
                QueryBuilder.start().and(
                        QueryBuilder.start("regionId").is(regionId).get(),
                        QueryBuilder.start("deleted").is(false).get()
                ).get(),
                DBProjection.exclude("request.scenario.modifications"),
                userPermissions
        );
    }

    private Collection<RegionalAnalysis> getRegionalAnalysesForRegion(Request req, Response res) {
        return getRegionalAnalysesForRegion(req.params("regionId"), UserPermissions.from(req));
    }

    // Note: this includes the modifications object which can be very large
    private RegionalAnalysis getRegionalAnalysis(Request req, Response res) {
        return Persistence.regionalAnalyses.findByIdIfPermitted(req.params("_id"), UserPermissions.from(req));
    }

    /**
     * Looks up all regional analyses for a region and checks the broker for jobs associated with them. If a JobStatus
     * exists it adds it to the list of running analyses.
     * @return JobStatues with associated regional analysis embedded
     */
    private Collection<JobStatus> getRunningAnalyses(Request req, Response res) {
        Collection<RegionalAnalysis> allAnalysesInRegion = getRegionalAnalysesForRegion(req.params("regionId"), UserPermissions.from(req));
        List<JobStatus> runningStatusesForRegion = new ArrayList<>();
        Collection<JobStatus> allJobStatuses = broker.getAllJobStatuses();
        for (RegionalAnalysis ra : allAnalysesInRegion) {
            JobStatus jobStatus = allJobStatuses.stream().filter(j -> j.jobId.equals(ra._id)).findFirst().orElse(null);
            if (jobStatus != null) {
                jobStatus.regionalAnalysis = ra;
                runningStatusesForRegion.add(jobStatus);
            }
        }

        return runningStatusesForRegion;
    }

    private RegionalAnalysis deleteRegionalAnalysis (Request req, Response res) {
        UserPermissions userPermissions = UserPermissions.from(req);
        RegionalAnalysis analysis = Persistence.regionalAnalyses.findPermitted(
                QueryBuilder.start().and(
                        QueryBuilder.start("_id").is(req.params("_id")).get(),
                        QueryBuilder.start("deleted").is(false).get()
                ).get(),
                DBProjection.exclude("request.scenario.modifications"),
                userPermissions
        ).iterator().next();
        analysis.deleted = true;
        Persistence.regionalAnalyses.updateByUserIfPermitted(analysis, userPermissions);

        // clear it from the broker
        if (!analysis.complete) {
            String jobId = analysis._id;
            if (broker.deleteJob(jobId)) {
                LOG.debug("Deleted job {} from broker.", jobId);
            } else {
                LOG.error("Deleting job {} from broker failed.", jobId);
            }
        }
        return analysis;
    }

    private int getIntQueryParameter (Request req, String parameterName, int defaultValue) {
        String paramValue = req.queryParams(parameterName);
        if (paramValue == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(paramValue);
        } catch (Exception ex) {
            String message = String.format(
                "Query parameter '%s' must be an integer, cannot parse '%s'.",
                parameterName,
                paramValue
            );
            throw new IllegalArgumentException(message, ex);
        }
    }

    /**
     * This used to extract a particular percentile of a regional analysis as a grid file.
     * Now it just gets the single percentile that exists for any one analysis, either from the local buffer file
     * for an analysis still in progress, or from S3 for a completed analysis.
     */
    private Object getRegionalResults (Request req, Response res) throws IOException {

        // Get some path parameters out of the URL.
        // The UUID of the regional analysis for which we want the output data
        final String regionalAnalysisId = req.params("_id");
        // The response file format: PNG, TIFF, or GRID
        final String fileFormatExtension = req.params("format");

        RegionalAnalysis analysis = Persistence.regionalAnalyses.findPermitted(
                QueryBuilder.start("_id").is(req.params("_id")).get(),
                DBProjection.exclude("request.scenario.modifications"),
                UserPermissions.from(req)
        ).iterator().next();

        if (analysis == null || analysis.deleted) {
            throw AnalysisServerException.notFound("The specified regional analysis is unknown or has been deleted.");
        }

        // Which channel to extract from results with multiple values per origin (for different travel time cutoffs)
        // and multiple output files per analysis (for different percentiles of travel time and/or different
        // destination pointsets). These initial values are for older regional analysis results with only a single
        // cutoff, and no percentile or destination gridId in the file name.
        // For newer analyses that have multiple cutoffs, percentiles, or destination pointsets, these initial values
        // are coming from deprecated fields, are not meaningful and will be overwritten below from query parameters.
        int percentile = analysis.travelTimePercentile;
        int cutoffMinutes = analysis.cutoffMinutes;
        int cutoffIndex = 0;
        String destinationPointSetId = analysis.grid;

        // Handle newer regional analyses with multiple cutoffs in an array.
        // If a query parameter is supplied, range check it, otherwise use the middle value in the list.
        // The cutoff variable holds the actual cutoff in minutes, not the position in the array of cutoffs.
        if (analysis.cutoffsMinutes != null) {
            int nCutoffs = analysis.cutoffsMinutes.length;
            checkState(nCutoffs > 0, "Regional analysis has no cutoffs.");
            cutoffMinutes = getIntQueryParameter(req, "cutoff", analysis.cutoffsMinutes[nCutoffs / 2]);
            cutoffIndex = new TIntArrayList(analysis.cutoffsMinutes).indexOf(cutoffMinutes);
            checkState(cutoffIndex >= 0,
                    "Travel time cutoff for this regional analysis must be taken from this list: (%s)",
                    Ints.join(", ", analysis.cutoffsMinutes)
            );
        }

        // Handle newer regional analyses with multiple percentiles in an array.
        // If a query parameter is supplied, range check it, otherwise use the middle value in the list.
        // The percentile variable holds the actual percentile (25, 50, 95) not the position in the array.
        if (analysis.travelTimePercentiles != null) {
            int nPercentiles = analysis.travelTimePercentiles.length;
            checkState(nPercentiles > 0, "Regional analysis has no percentiles.");
            percentile = getIntQueryParameter(req, "percentile", analysis.travelTimePercentiles[nPercentiles / 2]);
            checkArgument(new TIntArrayList(analysis.travelTimePercentiles).contains(percentile),
                    "Percentile for this regional analysis must be taken from this list: (%s)",
                    Ints.join(", ", analysis.travelTimePercentiles));
        }

        // Handle even newer regional analyses with multiple destination pointsets per analysis.
        if (analysis.destinationPointSetIds != null) {
            int nGrids = analysis.destinationPointSetIds.length;
            checkState(nGrids > 0, "Regional analysis has no grids.");
            destinationPointSetId = req.queryParams("destinationPointSetId");
            if (destinationPointSetId == null) {
                destinationPointSetId = analysis.destinationPointSetIds[0];
            }
            checkArgument(Arrays.asList(analysis.destinationPointSetIds).contains(destinationPointSetId),
                    "Destination gridId must be one of: %s",
                    String.join(",", analysis.destinationPointSetIds));
        }

        // We started implementing the ability to retrieve and display partially completed analyses.
        // We eventually decided these should not be available here at the same endpoint as complete, immutable results.

        if (broker.findJob(regionalAnalysisId) != null) {
            throw AnalysisServerException.notFound("Analysis is incomplete, no results file is available.");
        }

        // FIXME It is possible that regional analysis is complete, but UI is trying to fetch gridded results when there
        // aren't any (only CSV, because origins are freeform).
        // How can we determine whether this analysis is expected to have no gridded results and cleanly return a 404?

        // The analysis has already completed, results should be stored and retrieved from S3 via redirects.
        LOG.debug("Returning {} minute accessibility to pointset {} (percentile {}) for regional analysis {}.",
                cutoffMinutes, destinationPointSetId, percentile, regionalAnalysisId);
        FileStorageFormat format = FileStorageFormat.valueOf(fileFormatExtension.toUpperCase());
        if (!FileStorageFormat.GRID.equals(format) && !FileStorageFormat.PNG.equals(format) && !FileStorageFormat.GEOTIFF.equals(format)) {
            throw AnalysisServerException.badRequest("Format \"" + format + "\" is invalid. Request format must be \"grid\", \"png\", or \"tiff\".");
        }

        // Analysis grids now have the percentile and cutoff in their S3 key, because there can be many of each.
        // We do this even for results generated by older workers, so they will be re-extracted with the new name.
        // These grids are reasonably small, we may be able to just send all cutoffs to the UI instead of selecting.
        String singleCutoffKey =
                String.format("%s_%s_P%d_C%d.%s", regionalAnalysisId, destinationPointSetId, percentile, cutoffMinutes, fileFormatExtension);

        // A lot of overhead here - UI contacts backend, backend calls S3, backend responds to UI, UI contacts S3.
        FileStorageKey singleCutoffFileStorageKey = new FileStorageKey(RESULTS, singleCutoffKey);
        if (!fileStorage.exists(singleCutoffFileStorageKey)) {
            // An accessibility grid for this particular cutoff has apparently never been extracted from the
            // regional results file before. Extract one and save it for future reuse. Older regional analyses
            // did not have arrays allowing multiple cutoffs, percentiles, or destination pointsets. The
            // filenames of such regional accessibility results will not have a percentile or pointset ID.
            // First try the newest form of regional results: multi-percentile, multi-destination-grid.
            String multiCutoffKey = String.format("%s_%s_P%d.access", regionalAnalysisId, destinationPointSetId, percentile);
            FileStorageKey multiCutoffFileStorageKey = new FileStorageKey(RESULTS, multiCutoffKey);
            if (!fileStorage.exists(multiCutoffFileStorageKey)) {
                LOG.warn("Falling back to older file name formats for regional results file: " + multiCutoffKey);
                // Fall back to second-oldest form: multi-percentile, single destination grid.
                multiCutoffKey = String.format("%s_P%d.access", regionalAnalysisId, percentile);
                multiCutoffFileStorageKey = new FileStorageKey(RESULTS, multiCutoffKey);
                if (fileStorage.exists(multiCutoffFileStorageKey)) {
                    checkArgument(analysis.destinationPointSetIds.length == 1);
                } else {
                    // Fall back on oldest form of results, single-percentile, single-destination-grid.
                    multiCutoffKey = regionalAnalysisId + ".access";
                    multiCutoffFileStorageKey = new FileStorageKey(RESULTS, multiCutoffKey);
                    if (fileStorage.exists(multiCutoffFileStorageKey)) {
                        checkArgument(analysis.travelTimePercentiles.length == 1);
                        checkArgument(analysis.destinationPointSetIds.length == 1);
                    } else {
                        throw AnalysisServerException.notFound("Cannot find original source regional analysis output.");
                    }
                }
            }
            LOG.debug("Single-cutoff grid {} not found on S3, deriving it from {}.", singleCutoffKey, multiCutoffKey);

            InputStream multiCutoffInputStream = new FileInputStream(fileStorage.getFile(multiCutoffFileStorageKey));
            Grid grid = new SelectingGridReducer(cutoffIndex).compute(multiCutoffInputStream);

            File localFile = FileUtils.createScratchFile(format.toString());
            FileOutputStream fos = new FileOutputStream(localFile);

            switch (format) {
                case GRID:
                    grid.write(new GZIPOutputStream(fos));
                    break;
                case PNG:
                    grid.writePng(fos);
                    break;
                case GEOTIFF:
                    grid.writeGeotiff(fos);
                    break;
            }

            fileStorage.moveIntoStorage(singleCutoffFileStorageKey, localFile);
        }
        return JsonUtil.toJsonString(
                JsonUtil.objectNode().put("url", fileStorage.getURL(singleCutoffFileStorageKey))
        );
    }

    private String getCsvResults (Request req, Response res) {
        final String regionalAnalysisId = req.params("_id");
        final CsvResultType resultType = CsvResultType.valueOf(req.params("resultType").toUpperCase());
        // If the resultType parameter received on the API is unrecognized, valueOf throws IllegalArgumentException

        RegionalAnalysis analysis = Persistence.regionalAnalyses.findPermitted(
                QueryBuilder.start("_id").is(regionalAnalysisId).get(),
                DBProjection.exclude("request.scenario.modifications"),
                UserPermissions.from(req)
        ).iterator().next();

        if (analysis == null || analysis.deleted) {
            throw AnalysisServerException.notFound("The specified analysis is unknown, incomplete, or deleted.");
        }

        String storageKey = analysis.resultStorage.get(resultType);
        if (storageKey == null) {
            throw AnalysisServerException.notFound("This regional analysis does not contain CSV results of type " + resultType);
        }

        FileStorageKey fileStorageKey = new FileStorageKey(RESULTS, storageKey);

        res.type("text/plain");
        return fileStorage.getURL(fileStorageKey);
    }

    /**
     * Deserialize a description of a new regional analysis (an AnalysisRequest object) POSTed as JSON over the HTTP API.
     * Derive an internal RegionalAnalysis object, which is enqueued in the broker and also returned to the caller
     * in the body of the HTTP response.
     */
    private RegionalAnalysis createRegionalAnalysis (Request req, Response res) throws IOException {
        final UserPermissions userPermissions = UserPermissions.from(req);

        AnalysisRequest analysisRequest = JsonUtil.objectMapper.readValue(req.body(), AnalysisRequest.class);

        // If the UI has requested creation of a "static site", set all the necessary switches on the requests
        // that will go to the worker: break travel time down into waiting, riding, and walking, record paths to
        // destinations, and save results on S3.
        if (analysisRequest.name.contains("STATIC_SITE")) {
            // Hidden feature: allows us to run static sites experimentally without exposing a checkbox to all users.
            analysisRequest.makeTauiSite = true;
        }

        if (analysisRequest.name.contains("MULTI_CUTOFF")) {
            // Hidden feature: allows us to test multiple cutoffs and percentiles without modifying UI.
            // These arrays could also be sent in the API payload. Either way, they will override any single cutoff.
            analysisRequest.cutoffsMinutes = DEFAULT_CUTOFFS;
            analysisRequest.percentiles = DEFAULT_REGIONAL_PERCENTILES;
        }

        // Create an internal RegionalTask and RegionalAnalysis from the AnalysisRequest sent by the client.
        // TODO now this is setting cutoffs and percentiles in the regional (template) task.
        //   why is some stuff set in this populate method, and other things set here in the caller?
        RegionalTask task = new RegionalTask();
        analysisRequest.populateTask(task, userPermissions);

        // Set the destination PointSets, which are required for all non-Taui regional requests.
        if (!analysisRequest.makeTauiSite) {
            checkNotNull(analysisRequest.destinationPointSetIds);
            checkState(analysisRequest.destinationPointSetIds.length > 0,
                "At least one destination pointset ID must be supplied.");
            int nPointSets = analysisRequest.destinationPointSetIds.length;
            task.destinationPointSetKeys = new String[nPointSets];
            List<OpportunityDataset> opportunityDatasets = new ArrayList<>();
            for (int i = 0; i < nPointSets; i++) {
                String destinationPointSetId = analysisRequest.destinationPointSetIds[i];
                OpportunityDataset opportunityDataset = Persistence.opportunityDatasets.findByIdIfPermitted(
                        destinationPointSetId,
                        userPermissions
                );
                checkNotNull(opportunityDataset, "Opportunity dataset could not be found in database.");
                opportunityDatasets.add(opportunityDataset);
                task.destinationPointSetKeys[i] = opportunityDataset.storageLocation();
            }
            // For backward compatibility with old workers, communicate any single pointSet via the deprecated field.
            if (nPointSets == 1) {
                task.grid = task.destinationPointSetKeys[0];
            }
            // Check that we have either a single freeform pointset, or only gridded pointsets at indentical zooms.
            // The worker will perform equivalent checks via the GridTransformWrapper constructor,
            // WebMercatorExtents.expandToInclude and WebMercatorExtents.forPointsets. Potential to share code.
            for (OpportunityDataset dataset : opportunityDatasets) {
                if (dataset.format == FileStorageFormat.FREEFORM) {
                    checkArgument(
                        nPointSets == 1,
                        "If a freeform destination PointSet is specified, it must be the only one."
                    );
                } else {
                    checkArgument(
                        dataset.zoom == opportunityDatasets.get(0).zoom,
                        "If multiple grids are specified as destinations, they must have identical resolutions (web mercator zoom levels)."
                    );
                }
            }
            // Also do a preflight validation of the cutoffs and percentiles arrays for all non-TAUI regional tasks.
            task.validateCutoffsMinutes();
            task.validatePercentiles();
        }

        // Set the origin pointset key if an ID is specified. Currently this will always be a freeform pointset.
        // Also load this freeform origin pointset instance itself, so broker can see point coordinates, ids etc.
        if (analysisRequest.originPointSetId != null) {
            task.originPointSetKey = Persistence.opportunityDatasets
                    .findByIdIfPermitted(analysisRequest.originPointSetId, userPermissions).storageLocation();
            task.originPointSet = PointSetCache.readFreeFormFromFileStore(task.originPointSetKey);
        }

        task.oneToOne = analysisRequest.oneToOne;
        task.recordTimes = analysisRequest.recordTimes;
        // For now, we support calculating paths in regional analyses only for freeform origins.
        task.includePathResults = analysisRequest.originPointSetId != null && analysisRequest.recordPaths;
        task.recordAccessibility = analysisRequest.recordAccessibility;

        // Making a Taui site implies writing static travel time and path files per origin, but not accessibility.
        if (analysisRequest.makeTauiSite) {
            task.makeTauiSite = true;
            task.recordAccessibility = false;
        }

        // If our destinations are freeform, pre-load the destination pointset on the backend.
        // This allows MultiOriginAssembler to know the number of points, and in one-to-one mode to look up their IDs.
        // Initialization order is important here: task fields makeTauiSite and destinationPointSetKeys must already be
        // set above.
        if (!task.makeTauiSite && task.destinationPointSetKeys[0].endsWith(FileStorageFormat.FREEFORM.extension)) {
            checkArgument(task.destinationPointSetKeys.length == 1);
            task.destinationPointSets = new PointSet[] {
                    PointSetCache.readFreeFormFromFileStore(task.destinationPointSetKeys[0])
            };
        }
        if (task.recordTimes) {
            checkArgument(
                task.destinationPointSets != null &&
                task.destinationPointSets.length == 1 &&
                task.destinationPointSets[0] instanceof FreeFormPointSet,
                "recordTimes can only be used with a single destination pointset, which must be freeform (non-grid)."
            );
        }

        // TODO remove duplicate fields from RegionalAnalysis that are already in the nested task.
        // The RegionalAnalysis should just be a minimal wrapper around the template task, adding the origin point set.
        // The RegionalAnalysis object contains a reference to the worker task itself.
        // In fact, there are three separate classes all containing almost the same info:
        // AnalysisRequest (from UI to backend), RegionalTask (template sent to worker), RegionalAnalysis (in Mongo).
        // And for regional analyses, two instances of the worker task: the one with the scenario, and the templateTask.
        RegionalAnalysis regionalAnalysis = new RegionalAnalysis();
        regionalAnalysis.request = task;
        regionalAnalysis.height = task.height;
        regionalAnalysis.north = task.north;
        regionalAnalysis.west = task.west;
        regionalAnalysis.width = task.width;

        regionalAnalysis.accessGroup = userPermissions.accessGroup;
        regionalAnalysis.bundleId = analysisRequest.bundleId;
        regionalAnalysis.createdBy = userPermissions.email;
        regionalAnalysis.destinationPointSetIds = analysisRequest.destinationPointSetIds;
        regionalAnalysis.name = analysisRequest.name;
        regionalAnalysis.projectId = analysisRequest.projectId;
        regionalAnalysis.regionId = analysisRequest.regionId;
        regionalAnalysis.scenarioId = analysisRequest.scenarioId;
        regionalAnalysis.workerVersion = analysisRequest.workerVersion;
        regionalAnalysis.zoom = task.zoom;

        // Store the full array of multiple cutoffs which will be understood by newer workers and backends,
        // rather then the older single cutoff value.
        checkNotNull(analysisRequest.cutoffsMinutes);
        checkArgument(analysisRequest.cutoffsMinutes.length > 0);
        regionalAnalysis.cutoffsMinutes = analysisRequest.cutoffsMinutes;
        if (analysisRequest.cutoffsMinutes.length == 1) {
            // Ensure older workers expecting a single cutoff will still function.
            regionalAnalysis.cutoffMinutes = analysisRequest.cutoffsMinutes[0];
        } else {
            // Store invalid value in deprecated field (-1 was already used) to make it clear it should not be used.
            regionalAnalysis.cutoffMinutes = -2;
        }

        // Same process as for cutoffsMinutes, but for percentiles.
        checkNotNull(analysisRequest.percentiles);
        checkArgument(analysisRequest.percentiles.length > 0);
        regionalAnalysis.travelTimePercentiles = analysisRequest.percentiles;
        if (analysisRequest.percentiles.length == 1) {
            regionalAnalysis.travelTimePercentile = analysisRequest.percentiles[0];
        } else {
            regionalAnalysis.travelTimePercentile = -2;
        }

        // Propagate any changes to the cutoff and percentiles arrays down into the nested RegionalTask.
        // TODO propagate single (non-array) values for old workers
        // TODO propagate the other direction, setting these values when initializing the task
        task.cutoffsMinutes = regionalAnalysis.cutoffsMinutes;
        task.percentiles = regionalAnalysis.travelTimePercentiles;

        // Persist this newly created RegionalAnalysis to Mongo.
        // This assigns it creation/update time stamps and an ID, which is needed to name any output CSV files.
        regionalAnalysis = Persistence.regionalAnalyses.create(regionalAnalysis);

        // Register the regional job with the broker, which will distribute individual tasks to workers and track progress.
        broker.enqueueTasksForRegionalJob(regionalAnalysis);

        // Flush to the database any information added to the RegionalAnalysis object when it was enqueued.
        // This includes the paths of any CSV files that will be produced by this analysis.
        // TODO verify whether there is a reason to use regionalAnalyses.modifyWithoutUpdatingLock() or put().
        Persistence.regionalAnalyses.modifiyWithoutUpdatingLock(regionalAnalysis);

        return regionalAnalysis;
    }

    private RegionalAnalysis updateRegionalAnalysis (Request request, Response response) throws IOException {
        RegionalAnalysis regionalAnalysis = JsonUtil.objectMapper.readValue(request.body(), RegionalAnalysis.class);
        return Persistence.regionalAnalyses.updateByUserIfPermitted(regionalAnalysis, UserPermissions.from(request));
    }

    /**
     * Return a JSON-wrapped URL for the file in FileStorage containing the JSON representation of the scenario for
     * the given regional analysis.
     */
    private JsonNode getScenarioJsonUrl (Request request, Response response) {
        RegionalAnalysis regionalAnalysis = Persistence.regionalAnalyses
                .findByIdIfPermitted(request.params("_id"), UserPermissions.from(request));
        // In the persisted objects, regionalAnalysis.scenarioId seems to be null. Get it from the embedded request.
        final String networkId = regionalAnalysis.bundleId;
        final String scenarioId = regionalAnalysis.request.scenarioId;
        checkNotNull(networkId, "RegionalAnalysis did not contain a network ID.");
        checkNotNull(scenarioId, "RegionalAnalysis did not contain an embedded request with scenario ID.");
        String scenarioUrl = fileStorage.getURL(
                new FileStorageKey(BUNDLES, getScenarioFilename(regionalAnalysis.bundleId, scenarioId)));
        return JsonUtil.objectNode().put("url", scenarioUrl);
    }

    @Override
    public void registerEndpoints (spark.Service sparkService) {
        sparkService.path("/api/region", () -> {
            sparkService.get("/:regionId/regional", this::getRegionalAnalysesForRegion, toJson);
            sparkService.get("/:regionId/regional/running", this::getRunningAnalyses, toJson);
        });
        sparkService.path("/api/regional", () -> {
            // For grids, no transformer is supplied: render raw bytes or input stream rather than transforming to JSON.
            sparkService.get("/:_id", this::getRegionalAnalysis);
            sparkService.get("/:_id/grid/:format", this::getRegionalResults);
            sparkService.get("/:_id/csv/:resultType", this::getCsvResults);
            sparkService.get("/:_id/scenarioJsonUrl", this::getScenarioJsonUrl);
            sparkService.delete("/:_id", this::deleteRegionalAnalysis, toJson);
            sparkService.post("", this::createRegionalAnalysis, toJson);
            sparkService.put("/:_id", this::updateRegionalAnalysis, toJson);
        });
    }

}
