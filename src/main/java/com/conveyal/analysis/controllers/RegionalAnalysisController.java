package com.conveyal.analysis.controllers;

import com.conveyal.analysis.AnalysisServerException;
import com.conveyal.analysis.SelectingGridReducer;
import com.conveyal.analysis.UserPermissions;
import com.conveyal.analysis.components.TaskScheduler;
import com.conveyal.analysis.components.broker.Broker;
import com.conveyal.analysis.components.broker.JobStatus;
import com.conveyal.analysis.components.broker.WorkerTags;
import com.conveyal.analysis.models.AnalysisRequest;
import com.conveyal.analysis.models.OpportunityDataset;
import com.conveyal.analysis.models.RegionalAnalysis;
import com.conveyal.analysis.persistence.Persistence;
import com.conveyal.analysis.results.AccessCsvResultWriter;
import com.conveyal.analysis.results.BaseResultWriter;
import com.conveyal.analysis.results.CsvResultType;
import com.conveyal.analysis.results.GridResultType;
import com.conveyal.analysis.results.GridResultWriter;
import com.conveyal.analysis.results.PathCsvResultWriter;
import com.conveyal.analysis.results.TemporalDensityCsvResultWriter;
import com.conveyal.analysis.results.TimeCsvResultWriter;
import com.conveyal.analysis.util.HttpStatus;
import com.conveyal.analysis.util.JsonUtil;
import com.conveyal.file.FileStorage;
import com.conveyal.file.FileStorageFormat;
import com.conveyal.file.FileStorageKey;
import com.conveyal.file.FileUtils;
import com.conveyal.file.UrlWithHumanName;
import com.conveyal.r5.analyst.FreeFormPointSet;
import com.conveyal.r5.analyst.Grid;
import com.conveyal.r5.analyst.PointSet;
import com.conveyal.r5.analyst.PointSetCache;
import com.conveyal.r5.analyst.WebMercatorExtents;
import com.conveyal.r5.analyst.cluster.PathResult;
import com.conveyal.r5.analyst.cluster.RegionalTask;
import com.conveyal.r5.analyst.progress.Task;
import com.conveyal.r5.analyst.scenario.Scenario;
import com.conveyal.r5.util.SemVer;
import com.google.common.primitives.Ints;
import com.mongodb.QueryBuilder;

import gnu.trove.list.array.TIntArrayList;
import spark.Request;
import spark.Response;

import org.mongojack.DBProjection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.conveyal.analysis.util.JsonUtil.toJson;
import static com.conveyal.file.FileCategory.BUNDLES;
import static com.conveyal.file.FileCategory.RESULTS;
import static com.conveyal.r5.common.Util.notNullOrEmpty;
import static com.conveyal.r5.transit.TransportNetworkCache.getScenarioFilename;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static org.eclipse.jetty.http.MimeTypes.Type.APPLICATION_JSON;
import static org.eclipse.jetty.http.MimeTypes.Type.TEXT_HTML;
import static org.eclipse.jetty.http.MimeTypes.Type.TEXT_PLAIN;

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

    private static final int MAX_FREEFORM_OD_PAIRS = 16_000_000;

    private static final int MAX_FREEFORM_DESTINATIONS = 4_000_000;

    private static final Logger LOG = LoggerFactory.getLogger(RegionalAnalysisController.class);

    private final Broker broker;
    private final FileStorage fileStorage;
    private final TaskScheduler taskScheduler;

    public RegionalAnalysisController (Broker broker, FileStorage fileStorage, TaskScheduler taskScheduler) {
        this.broker = broker;
        this.fileStorage = fileStorage;
        this.taskScheduler = taskScheduler;
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
     * Generate a single-threshold results file of the given format from a multi-threshold file and store it.
     */
    private File generateSingleThresholdResultsFile (
        File multiThresholdFile,
        FileStorageKey singleThresholdKey,
        int thresholdIndex,
        FileStorageFormat fileFormat
    ) throws IOException {
        LOG.debug("Deriving single-cutoff grid {} from {}.", singleThresholdKey.path, multiThresholdFile.getName());

        Grid grid = new SelectingGridReducer(thresholdIndex).compute(FileUtils.getInputStream(multiThresholdFile));
        File localFile = FileUtils.createScratchFile(fileFormat.toString());

        switch (fileFormat) {
            case GRID:
                grid.write(FileUtils.getGzipOutputStream(localFile));
                break;
            case PNG:
                grid.writePng(FileUtils.getOutputStream(localFile));
                break;
            case GEOTIFF:
                grid.writeGeotiff(FileUtils.getOutputStream(localFile));
                break;
            default:
                throw new IllegalArgumentException("Unsupported file format: " + fileFormat);
        }
        LOG.debug("Finished deriving single-cutoff grid {}. Transferring to storage.", singleThresholdKey.path);
        fileStorage.moveIntoStorage(singleThresholdKey, localFile);
        LOG.debug("Finished transferring single-cutoff grid {} to storage.", singleThresholdKey.path);
        return localFile;
    }

    // Prevent multiple requests from creating the same files in parallel.
    // This could potentially be integrated into FileStorage with enum return values or an additional boolean method.
    private Set<String> filesBeingPrepared = Collections.synchronizedSet(new HashSet<>());

    private Object getAllRegionalResults (Request req, Response res) throws IOException {
        final String regionalAnalysisId = req.params("_id");
        final UserPermissions userPermissions = UserPermissions.from(req);
        final RegionalAnalysis analysis = getAnalysis(regionalAnalysisId, userPermissions);
        if (analysis.request.originPointSetKey != null) {
            throw AnalysisServerException.badRequest("Batch result download only available for gridded origins.");
        }
        FileStorageKey zippedResultsKey = new FileStorageKey(RESULTS, analysis._id + "_ALL.zip");
        if (fileStorage.exists(zippedResultsKey)) {
            res.type(APPLICATION_JSON.asString());
            String analysisHumanName = analysis.humanName();
            return fileStorage.getJsonUrl(zippedResultsKey, analysisHumanName, "zip");
        }
        if (filesBeingPrepared.contains(zippedResultsKey.path)) {
            res.type(TEXT_PLAIN.asString());
            res.status(HttpStatus.ACCEPTED_202);
            return "Geotiff zip is already being prepared in the background.";
        }
        // File did not exist. Create it in the background and ask caller to request it later.
        filesBeingPrepared.add(zippedResultsKey.path);
        Task task = Task.create("Zip all geotiffs for regional analysis " + analysis.name)
            .forUser(userPermissions)
            .withAction(progressListener -> {
                int nSteps = analysis.destinationPointSetIds.length * analysis.cutoffsMinutes.length *
                        analysis.travelTimePercentiles.length * 2 + 1;
                progressListener.beginTask("Creating and archiving geotiffs...", nSteps);
                // Iterate over all dest, cutoff, percentile combinations and generate one geotiff for each combination.
                Map<String, FileStorageKey> fileKeys = new HashMap<>();
                for (String destinationPointSetId : analysis.destinationPointSetIds) {
                    String destinationsName = getDestinationsName(destinationPointSetId, userPermissions);
                    for (int percentile : analysis.travelTimePercentiles) {
                        FileStorageKey multiKey = analysis.getMultiOriginAccessFileKey(destinationPointSetId, percentile);
                        File multiThresholdFile = fileStorage.getFile(multiKey);
                        for (int cutoffMinutes : analysis.cutoffsMinutes) {
                            FileStorageKey singleCutoffKey = analysis.getSingleCutoffGridFileKey(destinationPointSetId, percentile, cutoffMinutes, FileStorageFormat.GEOTIFF);
                            if (!fileStorage.exists(singleCutoffKey)) {
                                generateSingleThresholdResultsFile(multiThresholdFile, singleCutoffKey, cutoffMinutes, FileStorageFormat.GEOTIFF);
                            }
                            String humanName = analysis.getSingleCutoffGridFileKey(analysis.humanName(), destinationsName, percentile, cutoffMinutes, FileStorageFormat.GEOTIFF).path;
                            fileKeys.put(humanName, singleCutoffKey);
                            progressListener.increment();
                        }
                    }
                }
                File tempZipFile = File.createTempFile("regional", ".zip");
                // Zipfs can't open existing empty files, the file has to not exist. FIXME: Non-dangerous race condition
                // Examining ZipFileSystemProvider reveals a "useTempFile" env parameter, but this is for the individual
                // entries. May be better to just use zipOutputStream which would also allow gzip - zip CSV conversion.
                tempZipFile.delete();
                Map<String, String> env = Map.of("create", "true");
                URI uri = URI.create("jar:file:" + tempZipFile.getAbsolutePath());
                try (FileSystem zipFilesystem = FileSystems.newFileSystem(uri, env)) {
                    for (Map.Entry<String, FileStorageKey> entry : fileKeys.entrySet()) {
                        Path storagePath = fileStorage.getFile(entry.getValue()).toPath();
                        Path zipPath = zipFilesystem.getPath(entry.getKey());
                        Files.copy(storagePath, zipPath, StandardCopyOption.REPLACE_EXISTING);
                        progressListener.increment();
                    }
                }
                fileStorage.moveIntoStorage(zippedResultsKey, tempZipFile);
                progressListener.increment();
                filesBeingPrepared.remove(zippedResultsKey.path);
            });
        taskScheduler.enqueue(task);
        res.type(TEXT_PLAIN.asString());
        res.status(HttpStatus.ACCEPTED_202);
        return "Building geotiff zip in background.";
    }

    /** Fetch destination OpportunityDataset from database from its ID. If it exists, return its human name, otherwise return the ID. */
    private static String getDestinationsName (String destinationPointSetId, UserPermissions userPermissions) {
        OpportunityDataset opportunityDataset = Persistence.opportunityDatasets.findByIdIfPermitted(destinationPointSetId, userPermissions);
        if (opportunityDataset != null) return opportunityDataset.humanName();
        return destinationPointSetId;
    }

    /** Fetch RegionalAnalysis from database by ID, followed by a check that it was present and not deleted. */
    private static RegionalAnalysis getAnalysis (String analysisId, UserPermissions userPermissions) {
        RegionalAnalysis analysis = Persistence.regionalAnalyses.findPermitted(
                QueryBuilder.start("_id").is(analysisId).get(),
                DBProjection.exclude("request.scenario.modifications"),
                userPermissions
        ).iterator().next();
        if (analysis == null || analysis.deleted) {
            throw AnalysisServerException.notFound("The specified regional analysis is unknown or has been deleted.");
        }
        return analysis;
    }

    /** 
     * Extract a particular percentile and threshold of a regional analysis in one of several different raster formats. 
     */
    private UrlWithHumanName getRegionalResults (Request req, Response res) throws IOException {
        // It is possible that regional analysis is complete, but UI is trying to fetch gridded results when there
        // aren't any (only CSV, because origins are freeform). How should we determine whether this analysis is
        // expected to have no gridded results and cleanly return a 404?
        final String regionalAnalysisId = req.params("_id");
        final GridResultType accessType = GridResultType.valueOf(req.queryParamOrDefault("accessType", "ACCESS").toUpperCase());
        final FileStorageFormat format = FileStorageFormat.valueOf(req.params("format").toUpperCase());
        if (!FileStorageFormat.GRID.equals(format) && !FileStorageFormat.PNG.equals(format) && !FileStorageFormat.GEOTIFF.equals(format)) {
            throw AnalysisServerException.badRequest("Format \"" + format + "\" is invalid. Request format must be \"grid\", \"png\", or \"geotiff\".");
        }

        final UserPermissions userPermissions = UserPermissions.from(req);
        RegionalAnalysis analysis = getAnalysis(regionalAnalysisId, userPermissions);

        // We started implementing the ability to retrieve and display partially completed analyses.
        // We eventually decided these should not be available here at the same endpoint as complete, immutable results.
        if (broker.findJob(regionalAnalysisId) != null) {
            throw AnalysisServerException.notFound("Analysis is incomplete, no results file is available.");
        }

        // If a query parameter is supplied, range check it, otherwise use the middle value in the list.
        // The percentile variable holds the actual percentile (25, 50, 95) not the position in the array.
        int nPercentiles = analysis.travelTimePercentiles.length;
        checkState(nPercentiles > 0, "Regional analysis has no percentiles.");
        int percentile = getIntQueryParameter(req, "percentile", analysis.travelTimePercentiles[nPercentiles / 2]);
        checkArgument(new TIntArrayList(analysis.travelTimePercentiles).contains(percentile),
                "Percentile for this regional analysis must be taken from this list: (%s)",
                Ints.join(", ", analysis.travelTimePercentiles));

        // Handle regional analyses with multiple destination pointsets per analysis.
        int nGrids = analysis.destinationPointSetIds.length;
        checkState(nGrids > 0, "Regional analysis has no grids.");
        String destinationPointSetId = req.queryParams("destinationPointSetId");
        if (destinationPointSetId == null) {
            destinationPointSetId = analysis.destinationPointSetIds[0];
        }
        checkArgument(Arrays.asList(analysis.destinationPointSetIds).contains(destinationPointSetId),
                "Destination gridId must be one of: %s",
                String.join(",", analysis.destinationPointSetIds));
        String destinationsName = getDestinationsName(destinationPointSetId, userPermissions);

        int threshold;
        int thresholdIndex = 0;
        FileStorageKey singleThresholdKey;
        FileStorageKey multiKey;
        String humanName;
        if (analysis.request.includeTemporalDensity) {
            int nThresholds = analysis.request.dualAccessThresholds.length;
            int[] thresholds = analysis.request.dualAccessThresholds;
            checkState(nThresholds > 0, "Regional analysis has no thresholds.");
            threshold = getIntQueryParameter(req, "threshold", thresholds[nThresholds / 2]);
            thresholdIndex = new TIntArrayList(thresholds).indexOf(threshold);
            checkArgument(thresholdIndex >= 0,
                    "Dual access thresholds for this regional analysis must be taken from this list: (%s)",
                    Ints.join(", ", thresholds)
            );
            singleThresholdKey = analysis.getSingleThresholdDualAccessGridFileKey(destinationPointSetId, percentile, threshold, format);
            multiKey = analysis.getMultiOriginDualAccessFileKey(destinationPointSetId, percentile);
            humanName = analysis.getSingleThresholdDualAccessGridFileKey(analysis.humanName(), destinationsName, percentile, threshold, format).path;
        } else {
            // The cutoff variable holds the actual cutoff in minutes, not the position in the array of cutoffs.
            checkState(analysis.cutoffsMinutes != null, "Regional analysis has no cutoffs.");
            int nCutoffs = analysis.cutoffsMinutes.length;
            checkState(nCutoffs > 0, "Regional analysis has no cutoffs.");
            threshold = getIntQueryParameter(req, "threshold", analysis.cutoffsMinutes[nCutoffs / 2]);
            thresholdIndex = new TIntArrayList(analysis.cutoffsMinutes).indexOf(threshold);
            checkArgument(thresholdIndex >= 0,
                    "Travel time cutoff for this regional analysis must be taken from this list: (%s)",
                    Ints.join(", ", analysis.cutoffsMinutes)
            );
            singleThresholdKey = analysis.getSingleCutoffGridFileKey(destinationPointSetId, percentile, threshold, format);
            multiKey = analysis.getMultiOriginAccessFileKey(destinationPointSetId, percentile);
            humanName = analysis.getSingleCutoffGridFileKey(analysis.humanName(), destinationsName, percentile, threshold, format).path;
        }

        if (!fileStorage.exists(singleThresholdKey)) {
            File multiThresholdFile = fileStorage.getFile(multiKey);
            if (!multiThresholdFile.exists()) {
                throw AnalysisServerException.notFound("The specified analysis is unknown, incomplete, or deleted.");
            }
            generateSingleThresholdResultsFile(multiThresholdFile, singleThresholdKey, thresholdIndex, format);
        }
        
        res.type(APPLICATION_JSON.asString());
        return fileStorage.getJsonUrl(singleThresholdKey, humanName);
    }

    private Object getCsvResults (Request req, Response res) {
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

        if (!analysis.resultStorage.containsKey(resultType)) {
            throw AnalysisServerException.notFound("This regional analysis does not contain CSV results of type " + resultType);
        }

        FileStorageKey fileStorageKey = analysis.getCsvResultFileKey(resultType);

        // TODO handle JSON with human name on UI side
        // res.type(APPLICATION_JSON.asString());
        // return fileStorage.getJsonUrl(fileStorageKey, analysis.name, resultType + ".csv");
        res.type(TEXT_HTML.asString());
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

            // If results have been requested for freeform origins, check that the origin and
            // destination pointsets are not too big for generating CSV files.
            int nTasksTotal = task.width * task.height;
            if (task.originPointSet != null) {
                nTasksTotal = task.originPointSet.featureCount();
            }
            int nDestinations = task.destinationPointSets[0].featureCount();
            int nODPairs = task.oneToOne ? nTasksTotal : nTasksTotal * nDestinations;
            if (task.recordTimes &&
                (nDestinations > MAX_FREEFORM_DESTINATIONS || nODPairs > MAX_FREEFORM_OD_PAIRS)) {
                throw AnalysisServerException.badRequest(String.format(
                    "Travel time results limited to %d destinations and %d origin-destination pairs.",
                    MAX_FREEFORM_DESTINATIONS, MAX_FREEFORM_OD_PAIRS
                ));
            }
            if (task.includePathResults &&
                (nDestinations > PathResult.MAX_PATH_DESTINATIONS || nODPairs > MAX_FREEFORM_OD_PAIRS)) {
                throw AnalysisServerException.badRequest(String.format(
                    "Path results limited to %d destinations and %d origin-destination pairs.",
                    PathResult.MAX_PATH_DESTINATIONS, MAX_FREEFORM_OD_PAIRS
                ));
            } 
        }
        if (task.recordTimes) {
            checkArgument(
                task.destinationPointSets != null &&
                task.destinationPointSets.length == 1 &&
                task.destinationPointSets[0] instanceof FreeFormPointSet,
                "recordTimes can only be used with a single destination pointset, which must be freeform (non-grid)."
            );
        }
        if (task.includeTemporalDensity) {
            task.validateDualAccessThresholds();

            checkArgument(
                    SemVer.gte(task.workerVersion, "v7.4"),
                    "Dual access results require a minimum worker version of v7.4"
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

        // Store the full array of multiple cutoffs and percentiles.
        regionalAnalysis.cutoffsMinutes = analysisRequest.cutoffsMinutes;
        regionalAnalysis.travelTimePercentiles = analysisRequest.percentiles;

        // Persist this newly created RegionalAnalysis to Mongo.
        // This assigns it creation/update time stamps and an ID, which is needed to name any output files.
        regionalAnalysis = Persistence.regionalAnalyses.create(regionalAnalysis);

        // Set the job ID on the task, which is used by the MultiOriginAssembler and Broker.
        task.jobId = regionalAnalysis._id;

        // Create the result writers. The file names need the job ID.
        Map<FileStorageKey, BaseResultWriter> resultWriters = new HashMap<>();
        if (task.originPointSet == null) {
            WebMercatorExtents extents = task.getWebMercatorExtents();
            for (int destinationsIndex = 0; destinationsIndex < task.destinationPointSetKeys.length; destinationsIndex++) {
                for (int percentilesIndex = 0; percentilesIndex < task.percentiles.length; percentilesIndex++) {
                    if (task.recordAccessibility) {
                        FileStorageKey fileKey = regionalAnalysis.getMultiOriginAccessFileKey(
                            regionalAnalysis.destinationPointSetIds[destinationsIndex],
                            task.percentiles[percentilesIndex]
                        );
                        resultWriters.put(fileKey, new GridResultWriter(
                                GridResultType.ACCESS,
                                extents,
                                destinationsIndex,
                                percentilesIndex,
                                task.cutoffsMinutes.length
                        ));
                    } 

                    if (task.includeTemporalDensity) {
                        FileStorageKey fileKey = regionalAnalysis.getMultiOriginDualAccessFileKey(
                            regionalAnalysis.destinationPointSetIds[destinationsIndex],
                            task.percentiles[percentilesIndex]
                        );
                        resultWriters.put(fileKey, new GridResultWriter(
                                GridResultType.DUAL_ACCESS,
                                extents,
                                destinationsIndex,
                                percentilesIndex,
                                task.dualAccessThresholds.length
                        ));
                    }
                }
            }
        } else {
            if (task.recordAccessibility) {
                // Freeform origins - create CSV regional analysis results
                FileStorageKey fileKey = regionalAnalysis.getCsvResultFileKey(CsvResultType.ACCESS);
                resultWriters.put(fileKey, new AccessCsvResultWriter(task));
                regionalAnalysis.resultStorage.put(CsvResultType.ACCESS, fileKey.path);
            }

            if (task.includeTemporalDensity) {
                FileStorageKey fileKey = regionalAnalysis.getCsvResultFileKey(CsvResultType.TDENSITY);
                resultWriters.put(fileKey, new TemporalDensityCsvResultWriter(task));
                regionalAnalysis.resultStorage.put(CsvResultType.TDENSITY, fileKey.path);
            }
        }

        if (task.recordTimes) {
            FileStorageKey fileKey = regionalAnalysis.getCsvResultFileKey(CsvResultType.TIMES);
            resultWriters.put(fileKey, new TimeCsvResultWriter(task));
            regionalAnalysis.resultStorage.put(CsvResultType.TIMES, fileKey.path);
        }

        if (task.includePathResults) {
            FileStorageKey fileKey = regionalAnalysis.getCsvResultFileKey(CsvResultType.PATHS);
            resultWriters.put(fileKey, new PathCsvResultWriter(task));
            regionalAnalysis.resultStorage.put(CsvResultType.PATHS, fileKey.path);
        }

        checkArgument(task.makeTauiSite || notNullOrEmpty(resultWriters),"A non-Taui regional analysis should always create at least one grid or CSV file.");

        // Store the scenario JSON file.
        storeScenarioJson(regionalAnalysis, task.scenario);

        // Register the regional job with the broker, which will distribute individual tasks to workers and track progress.
        broker.enqueueTasksForRegionalJob(task, resultWriters, WorkerTags.fromRegionalAnalysis(regionalAnalysis));

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

    private void storeScenarioJson (RegionalAnalysis regionalAnalysis, Scenario scenario) {
        FileStorageKey fileStorageKey = regionalAnalysis.getScenarioJsonFileKey(scenario.id);
        try {
            File localScenario = FileUtils.createScratchFile("json");
            JsonUtil.objectMapper.writeValue(localScenario, scenario);
            fileStorage.moveIntoStorage(fileStorageKey, localScenario);
        } catch (IOException e) {
            LOG.error("Error storing scenario for retrieval by workers.", e);
        }
    }

    /**
     * Return a JSON-wrapped URL for the file in FileStorage containing the JSON representation of the scenario for
     * the given regional analysis.
     */
    private UrlWithHumanName getScenarioJsonUrl (Request request, Response response) {
        RegionalAnalysis regionalAnalysis = Persistence.regionalAnalyses.findByIdIfPermitted(
                request.params("_id"),
                DBProjection.exclude("request.scenario.modifications"),
                UserPermissions.from(request)
        );
        // In the persisted objects, regionalAnalysis.scenarioId seems to be null. Get it from the embedded request.
        final String networkId = regionalAnalysis.bundleId;
        final String scenarioId = regionalAnalysis.request.scenarioId;
        checkNotNull(networkId, "RegionalAnalysis did not contain a network ID.");
        checkNotNull(scenarioId, "RegionalAnalysis did not contain an embedded request with scenario ID.");
        FileStorageKey scenarioKey = new FileStorageKey(BUNDLES, getScenarioFilename(regionalAnalysis.bundleId, scenarioId));
        response.type(APPLICATION_JSON.asString());
        return fileStorage.getJsonUrl(scenarioKey, regionalAnalysis.name, "scenario.json");
    }

    @Override
    public void registerEndpoints (spark.Service sparkService) {
        sparkService.path("/api/region", () -> {
            sparkService.get("/:regionId/regional", this::getRegionalAnalysesForRegion, toJson);
            sparkService.get("/:regionId/regional/running", this::getRunningAnalyses, toJson);
        });
        sparkService.path("/api/regional", () -> {
            sparkService.get("/:_id", this::getRegionalAnalysis);
            sparkService.get("/:_id/all", this::getAllRegionalResults, toJson);
            sparkService.get("/:_id/grid/:format", this::getRegionalResults, toJson);
            sparkService.get("/:_id/csv/:resultType", this::getCsvResults);
            sparkService.get("/:_id/scenarioJsonUrl", this::getScenarioJsonUrl, toJson);
            sparkService.delete("/:_id", this::deleteRegionalAnalysis, toJson);
            sparkService.post("", this::createRegionalAnalysis, toJson);
            sparkService.put("/:_id", this::updateRegionalAnalysis, toJson);
        });
    }

}
