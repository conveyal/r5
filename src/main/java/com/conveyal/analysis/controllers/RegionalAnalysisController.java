package com.conveyal.analysis.controllers;

import com.conveyal.analysis.AnalysisServerException;
import com.conveyal.analysis.SelectingGridReducer;
import com.conveyal.analysis.UserPermissions;
import com.conveyal.analysis.components.broker.Broker;
import com.conveyal.analysis.components.broker.Job;
import com.conveyal.analysis.components.broker.JobStatus;
import com.conveyal.analysis.components.broker.WorkerTags;
import com.conveyal.analysis.models.AnalysisRequest;
import com.conveyal.analysis.models.RegionalAnalysis;
import com.conveyal.analysis.persistence.AnalysisDB;
import com.conveyal.analysis.results.AccessCsvResultWriter;
import com.conveyal.analysis.results.CsvResultType;
import com.conveyal.analysis.results.DbResultWriter;
import com.conveyal.analysis.results.GridResultWriter;
import com.conveyal.analysis.results.MultiOriginAssembler;
import com.conveyal.analysis.results.PathCsvResultWriter;
import com.conveyal.analysis.results.RegionalResultWriter;
import com.conveyal.analysis.results.TimeCsvResultWriter;
import com.conveyal.analysis.util.JsonUtil;
import com.conveyal.file.FileStorage;
import com.conveyal.file.FileStorageFormat;
import com.conveyal.file.FileStorageKey;
import com.conveyal.file.FileUtils;
import com.conveyal.r5.analyst.Grid;
import com.conveyal.r5.analyst.scenario.Scenario;
import com.fasterxml.jackson.databind.JsonNode;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Projections;
import com.mongodb.client.model.Updates;
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
import java.util.Collection;
import java.util.stream.Collectors;
import java.util.zip.GZIPOutputStream;

import static com.conveyal.analysis.util.JsonUtil.toJson;
import static com.conveyal.file.FileCategory.BUNDLES;
import static com.conveyal.file.FileCategory.RESULTS;
import static com.conveyal.r5.common.Util.notNullOrEmpty;
import static com.conveyal.r5.transit.TransportNetworkCache.getScenarioFilename;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

/**
 * Spark HTTP handler methods that allow launching new regional analyses, as well as deleting them and fetching
 * information about them.
 */
public class RegionalAnalysisController implements HttpController {

    private static final Logger LOG = LoggerFactory.getLogger(RegionalAnalysisController.class);

    private final AnalysisDB db;
    private final Broker broker;
    private final FileStorage fileStorage;

    public RegionalAnalysisController(Broker broker, AnalysisDB db, FileStorage fileStorage) {
        this.broker = broker;
        this.db = db;
        this.fileStorage = fileStorage;
    }

    /**
     * Fetch the regional analysis, except for the request object.
     */
    private RegionalAnalysis getRegionalAnalysis(Request req) {
        return db.regionalAnalyses
                .findPermitted(Filters.eq("_id", req.params("_id")), UserPermissions.from(req))
                .projection(Projections.exclude("request"))
                .first();
    }

    /**
     * Looks up all regional analyses for a region and checks the broker for jobs associated with them. If a JobStatus
     * exists it adds it to the list of running analyses.
     *
     * @return JobStatues with associated regional analysis embedded
     */
    private Collection<JobStatus> getRunningAnalyses(Request req, Response res) {
        final var regionId = req.params("regionId");
        final var user = UserPermissions.from(req);
        var iterator = db.regionalAnalyses.findPermitted(
                Filters.and(Filters.eq("regionId", regionId), Filters.eq("deleted", false)),
                user
        ).projection(Projections.exclude("request"));
        var runningStatusesForRegion = new ArrayList<JobStatus>();
        var allJobStatuses = broker.getAllJobStatuses();
        iterator.forEach(ra -> {
            JobStatus jobStatus = allJobStatuses.stream().filter(j -> j.jobId.equals(ra._id)).findFirst().orElse(null);
            if (jobStatus != null) {
                jobStatus.regionalAnalysis = ra;
                runningStatusesForRegion.add(jobStatus);
            }
        });
        return runningStatusesForRegion;
    }

    private RegionalAnalysis deleteRegionalAnalysis (Request req, Response res) {
        var analysis = getRegionalAnalysis(req);
        db.regionalAnalyses.collection.updateOne(
                Filters.eq("_id", analysis._id),
                Updates.set("deleted", true)
        );

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

        RegionalAnalysis analysis = getRegionalAnalysis(req);
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
            int nCutoffs = analysis.cutoffsMinutes.size();
            checkState(nCutoffs > 0, "Regional analysis has no cutoffs.");
            cutoffMinutes = getIntQueryParameter(req, "cutoff", analysis.cutoffsMinutes.get(nCutoffs / 2));
            cutoffIndex = analysis.cutoffsMinutes.indexOf(cutoffMinutes);
            checkState(cutoffIndex >= 0,
                    "Travel time cutoff for this regional analysis must be taken from this list: (%s)",
                    analysis.cutoffsMinutes.stream().map(Object::toString).collect(Collectors.joining(","))
            );
        }

        // Handle newer regional analyses with multiple percentiles in an array.
        // If a query parameter is supplied, range check it, otherwise use the middle value in the list.
        // The percentile variable holds the actual percentile (25, 50, 95) not the position in the array.
        if (analysis.travelTimePercentiles != null) {
            int nPercentiles = analysis.travelTimePercentiles.size();
            checkState(nPercentiles > 0, "Regional analysis has no percentiles.");
            percentile = getIntQueryParameter(req, "percentile", analysis.travelTimePercentiles.get(nPercentiles / 2));
            checkArgument(analysis.travelTimePercentiles.contains(percentile),
                    "Percentile for this regional analysis must be taken from this list: (%s)",
                    analysis.travelTimePercentiles.stream().map(Object::toString).collect(Collectors.joining(", ")));
        }

        // Handle even newer regional analyses with multiple destination pointsets per analysis.
        if (analysis.destinationPointSetIds != null) {
            int nGrids = analysis.destinationPointSetIds.size();
            checkState(nGrids > 0, "Regional analysis has no grids.");
            destinationPointSetId = req.queryParams("destinationPointSetId");
            if (destinationPointSetId == null) {
                destinationPointSetId = analysis.destinationPointSetIds.get(0);
            }
            checkArgument(analysis.destinationPointSetIds.contains(destinationPointSetId),
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
                    checkArgument(analysis.destinationPointSetIds.size() == 1);
                } else {
                    // Fall back on oldest form of results, single-percentile, single-destination-grid.
                    multiCutoffKey = regionalAnalysisId + ".access";
                    multiCutoffFileStorageKey = new FileStorageKey(RESULTS, multiCutoffKey);
                    if (fileStorage.exists(multiCutoffFileStorageKey)) {
                        checkArgument(analysis.travelTimePercentiles.size() == 1);
                        checkArgument(analysis.destinationPointSetIds.size() == 1);
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
        final CsvResultType resultType = CsvResultType.valueOf(req.params("resultType").toUpperCase());
        // If the resultType parameter received on the API is unrecognized, valueOf throws IllegalArgumentException

        RegionalAnalysis analysis = getRegionalAnalysis(req);
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
        final var userPermissions = UserPermissions.from(req);
        final var analysisRequest = JsonUtil.objectMapper.readValue(req.body(), AnalysisRequest.class);

        // Create an internal RegionalTask and RegionalAnalysis from the AnalysisRequest sent by the client.
        var task = analysisRequest.makeTauiSite
                ? analysisRequest.toTauiTask(db, userPermissions)
                : analysisRequest.toRegionalTask(db, userPermissions);
        var regionalAnalysis = new RegionalAnalysis(userPermissions, analysisRequest.name);

        // Set the Job ID to the same as the regional analysis ID
        task.jobId = regionalAnalysis._id;

        // TODO remove duplicate fields from RegionalAnalysis that are already in the nested task.
        // The RegionalAnalysis should just be a minimal wrapper around the template task, adding the origin point set.
        // The RegionalAnalysis object contains a reference to the worker task itself.
        // In fact, there are three separate classes all containing almost the same info:
        // AnalysisRequest (from UI to backend), RegionalTask (template sent to worker), RegionalAnalysis (in Mongo).
        // And for regional analyses, two instances of the worker task: the one with the scenario, and the templateTask.
        regionalAnalysis.request = task;
        regionalAnalysis.height = task.height;
        regionalAnalysis.north = task.north;
        regionalAnalysis.west = task.west;
        regionalAnalysis.width = task.width;

        regionalAnalysis.bundleId = analysisRequest.bundleId;
        regionalAnalysis.destinationPointSetIds = analysisRequest.destinationPointSetIds;
        regionalAnalysis.projectId = analysisRequest.projectId;
        regionalAnalysis.regionId = analysisRequest.regionId;
        regionalAnalysis.scenarioId = analysisRequest.scenarioId;
        regionalAnalysis.workerVersion = analysisRequest.workerVersion;
        regionalAnalysis.zoom = analysisRequest.zoom;

        // Store the full array of multiple cutoffs which will be understood by newer workers and backends,
        // rather than the older single cutoff value.
        checkNotNull(task.cutoffsMinutes);
        checkArgument(task.cutoffsMinutes.size() > 0);
        regionalAnalysis.cutoffsMinutes = task.cutoffsMinutes;
        if (task.cutoffsMinutes.size() == 1) {
            // Ensure older workers expecting a single cutoff will still function.
            regionalAnalysis.cutoffMinutes = task.cutoffsMinutes.get(0);
        } else {
            // Store invalid value in deprecated field (-1 was already used) to make it clear it should not be used.
            regionalAnalysis.cutoffMinutes = -2;
        }

        // Same process as for cutoffsMinutes, but for percentiles.
        checkNotNull(task.percentiles);
        checkArgument(task.percentiles.size() > 0);
        regionalAnalysis.travelTimePercentiles = task.percentiles;
        if (task.percentiles.size() == 1) {
            regionalAnalysis.travelTimePercentile = task.percentiles.get(0);
        } else {
            regionalAnalysis.travelTimePercentile = -2;
        }

        // Create the regional job
        var regionalJob = new Job(task, WorkerTags.fromRegionalAnalysis(regionalAnalysis));

        // Create the result writers. Store their result file paths in the database.
        var resultWriters = new ArrayList<RegionalResultWriter>();
        if (!task.makeTauiSite) {
            if (task.recordAccessibility) {
                if (task.originPointSet != null) {
                    var accessWriter = new AccessCsvResultWriter(task, fileStorage);
                    resultWriters.add(accessWriter);
                    regionalAnalysis.resultStorage.put(accessWriter.resultType().toString(), accessWriter.getFileName());
                } else {
                    resultWriters.addAll(GridResultWriter.createWritersFromTask(regionalAnalysis, task, fileStorage));
                }
            }

            if (task.recordTimes) {
                var timesWriter = new TimeCsvResultWriter(task, fileStorage);
                resultWriters.add(timesWriter);
                regionalAnalysis.resultStorage.put(timesWriter.resultType().toString(), timesWriter.getFileName());
            }

            if (task.includePathResults) {
                var pathsWriter = new PathCsvResultWriter(task, fileStorage);
                resultWriters.add(pathsWriter);
                regionalAnalysis.resultStorage.put(pathsWriter.resultType().toString(), pathsWriter.getFileName());
            }
            checkArgument(notNullOrEmpty(resultWriters), "A regional analysis should always create at least one grid or CSV file.");
        }
        // Add a resultWriter that will eventually mark this regionalAnalysis complete in the database.
        // In the future, we could enhance this to track progress in the database.
        resultWriters.add(new DbResultWriter(db, regionalAnalysis._id));


        // Persist this newly created RegionalAnalysis to the database.
        db.regionalAnalyses.insert(regionalAnalysis);

        // Create the multi-origin assembler
        var assembler = new MultiOriginAssembler(regionalJob, resultWriters);

        // Stored scenario is needed by workers. Must be done ahead of enqueueing the job.
        storeScenarioJson(task.graphId, task.scenario);

        // Register the regional job with the broker, which will distribute individual tasks to workers and track progress.
        broker.enqueueTasksForRegionalJob(regionalJob, assembler);

        return regionalAnalysis;
    }

    /**
     * Store the regional analysis scenario as JSON for retrieval by the workers.
     */
    private void storeScenarioJson(String graphId, Scenario scenario) throws IOException {
        String fileName = String.format("%s_%s.json", graphId, scenario.id);
        FileStorageKey fileStorageKey = new FileStorageKey(BUNDLES, fileName);
        File localScenario = FileUtils.createScratchFile("json");
        JsonUtil.objectMapper.writeValue(localScenario, scenario);
        fileStorage.moveIntoStorage(fileStorageKey, localScenario);
    }

    /**
     * Return a JSON-wrapped URL for the file in FileStorage containing the JSON representation of the scenario for
     * the given regional analysis.
     */
    private JsonNode getScenarioJsonUrl(Request request, Response response) {
        var user = UserPermissions.from(request);
        var regionalAnalysis = db.regionalAnalyses
                .findPermitted(Filters.eq("_id", request.params("_id")), user)
                .projection(Projections.include("bundleId", "request.scenarioId"))
                .first();
        // In the persisted objects, regionalAnalysis.scenarioId seems to be null. Get it from the embedded request.
        final String scenarioId = regionalAnalysis.request.scenarioId;
        checkNotNull(regionalAnalysis.bundleId, "RegionalAnalysis did not contain a bundle ID.");
        checkNotNull(scenarioId, "RegionalAnalysis did not contain an embedded request with scenario ID.");
        String scenarioUrl = fileStorage.getURL(
                new FileStorageKey(BUNDLES, getScenarioFilename(regionalAnalysis.bundleId, scenarioId)));
        return JsonUtil.objectNode().put("url", scenarioUrl);
    }

    @Override
    public void registerEndpoints (spark.Service sparkService) {
        sparkService.path("/api/region", () -> {
            sparkService.get("/:regionId/regional/running", this::getRunningAnalyses, toJson);
        });
        sparkService.path("/api/regional", () -> {
            // For grids, no transformer is supplied: render raw bytes or input stream rather than transforming to JSON.
            sparkService.get("/:_id/grid/:format", this::getRegionalResults);
            sparkService.get("/:_id/csv/:resultType", this::getCsvResults);
            sparkService.get("/:_id/scenarioJsonUrl", this::getScenarioJsonUrl);
            sparkService.delete("/:_id", this::deleteRegionalAnalysis, toJson);
            sparkService.post("", this::createRegionalAnalysis, toJson);
        });
    }

}
