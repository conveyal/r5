package com.conveyal.analysis.results;

import com.conveyal.analysis.AnalysisServerException;
import com.conveyal.analysis.components.broker.Job;
import com.conveyal.analysis.models.RegionalAnalysis;
import com.conveyal.file.FileStorage;
import com.conveyal.file.FileStorageFormat;
import com.conveyal.r5.analyst.PointSet;
import com.conveyal.r5.analyst.PointSetCache;
import com.conveyal.r5.analyst.cluster.RegionalWorkResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.BitSet;

/**
 * This assembles regional results arriving from workers into one or more files per regional analysis on
 * the backend. This is not a singleton component: one MultiOriginAssembler instance is created per currently active
 * job awaiting results from workers. It delegates to ResultWriters to actually slot results into different file formats.
 */
public class MultiOriginAssembler {

    public static final Logger LOG = LoggerFactory.getLogger(MultiOriginAssembler.class);

    /**
     * The regional analysis for which this object is assembling results.
     * We retain the whole object rather than just its ID so we'll have the full details, e.g. destination point set
     * IDs and scenario, things that are stripped out of the template task sent to the workers.
     */
    private final RegionalAnalysis regionalAnalysis;

    /**
     * The object representing the progress of the regional analysis as tracked by the broker.
     * It may appear job.templateTask has all the information needed, making the regionalAnalysis field
     * unnecessary. But the templateTask is for worker consumption, while the regionalAnalysis has fields that are more
     * readily usable in the backend assembler (e.g. destination pointset id, instead of full storage key).
     */
    public final Job job;

    /**
     * We create one GridResultWriter for each destination pointset and percentile.  Each of those output files
     * contains data for all travel time cutoffs at each origin.
     */
    private GridResultWriter[][] accessibilityGridWriters;

    private CsvResultWriter timeCsvWriter;

    private CsvResultWriter accessibilityCsvWriter;

    /** For the time being this field is only set when the origins are freeform (rather than a grid). */
    private PointSet originPointSet;

    /** For the time being this field is only set when the destinations are freeform (rather than a grid). */
    private PointSet destinationPointSet;

    // TODO the ResultWriters and associated booleans could potentially be replaced with a list and polymorphism
    //      e.g. for (ResultWriter rw : resultWriters) rw.writeOne(RegionalWorkResult workResult);

    private boolean writeAccessibilityGrid = false;

    private boolean writeTimeCsv = false;

    private boolean writeAccessibilityCsv = false;

    private boolean error = false;

    /**
     * The number of distinct origin points for which we've received at least one result. If for
     * whatever reason we receive two or more results for the same origin this should only be
     * incremented once). It's incremented in a synchronized block to avoid race conditions.
     */
    public int nComplete = 0;

    /**
     * We need to keep track of which specific origins are completed, to avoid double counting if we
     * receive more than one result for the same origin. As with nComplete, access must be
     * synchronized to avoid race conditions. The nComplete field could be derived from this BitSet,
     * but nComplete can be read in constant time whereas counting true bits in a BitSet takes
     * linear time.
     * FIXME it doesn't seem like both the Job and the MultiOriginAssembler should be tracking job progress.
     *       Might be preferable to track this only in the job, and have it close the assembler when the job finishes.
     */
    private final BitSet originsReceived;

    /**
     * Total number of origin points for which we're expecting results. Note that the total
     * number of results received could be higher in the event of an overzealous task redelivery.
     */
    public final int nOriginsTotal;

    /** The number of different percentiles for which we're calculating accessibility on the workers. */
    private final int nPercentiles;

    /** The number of destination pointsets to which we're calculating accessibility */
    private final int nDestinationPointSets;

    /**
     * The number of different travel time cutoffs being applied when computing accessibility for each origin. This
     * is the number of values stored per origin cell in an accessibility results grid.
     * Note that we're storing only the number of different cutoffs, but not the cutoff values themselves in the file.
     * This means that the files can only be properly interpreted with the Mongo metadata from the regional analysis.
     * This is an intentional choice to avoid changing the file format, and in any case these files are not expected
     * to ever be used separately from an environment where the Mongo database is available.
     */
    private final int nCutoffs;

    /**
     * Constructor. This sets up one or more ResultWriters depending on whether we're writing gridded or non-gridded
     * cumulative opportunities accessibility, or origin-destination travel times.
     * TODO do not pass the FileStorage component down into this non-component and the ResultWriter non-component,
     *      clarify design concepts on this point (e.g. only components should know other components exist).
     *      Rather than pushing the component all the way down to the leaf function call, we return the finished
     *      file up to an umbrella location where a single reference to the file storage can be used to
     *      store all of them.
     */
    public MultiOriginAssembler (RegionalAnalysis regionalAnalysis, Job job, String outputBucket,
                                 FileStorage fileStorage) {

        this.regionalAnalysis = regionalAnalysis;
        this.job = job;
        this.nPercentiles = job.templateTask.percentiles.length;
        // Newly launched analyses have the cutoffs field, even when being sent to old workers that don't read it.
        this.nCutoffs = job.templateTask.cutoffsMinutes.length;
        this.nDestinationPointSets = job.templateTask.makeTauiSite ? 0 :
                job.templateTask.destinationPointSetKeys.length;
        this.nOriginsTotal = job.nTasksTotal;
        this.originsReceived = new BitSet(job.nTasksTotal);
        this.originPointSet = job.originPointSet;
        try {
            if (job.templateTask.recordAccessibility) {
                if (job.originPointSet != null) {
                    LOG.info(
                        "Creating CSV file to store accessibility results for {} origins.",
                        job.nTasksTotal
                    );
                    accessibilityCsvWriter = new CsvResultWriter(
                        job.templateTask, "accessibility", outputBucket, fileStorage
                    );
                    writeAccessibilityCsv = true;
                } else {
                    // Create one grid writer per percentile and destination pointset
                    accessibilityGridWriters = new GridResultWriter[nDestinationPointSets][nPercentiles];
                    for (int d = 0; d < nDestinationPointSets; d++) {
                        for (int p = 0; p < nPercentiles; p++) {
                            accessibilityGridWriters[d][p] =
                                    new GridResultWriter(job.templateTask, outputBucket, fileStorage);
                        }
                    }
                    writeAccessibilityGrid = true;
                }
            }

            if (!job.templateTask.makeTauiSite && job.templateTask.destinationPointSetKeys[0].endsWith(FileStorageFormat.FREEFORM.extension)) {
                // It's kind of fragile to read from an external network service here. But this is
                // only triggered when destinations are freeform, which is an experimental feature.
                destinationPointSet = PointSetCache.readFreeFormFromFileStore(job.templateTask.grid);
                if (job.templateTask.recordTimes && !job.templateTask.oneToOne) {
                    if (nOriginsTotal * destinationPointSet.featureCount() > 1_000_000) {
                        error = true;
                        throw new AnalysisServerException("Temporarily limited to 1 million origin-destination pairs");
                    }
                }
            }

            if (job.templateTask.recordTimes) {
                LOG.info("Creating csv file to store time results for {} origins.", job.nTasksTotal);
                timeCsvWriter = new CsvResultWriter(job.templateTask, "time", outputBucket, fileStorage);
                writeTimeCsv = true;
            }

        } catch (IOException e) {
            error = true;
            LOG.error("Exception while creating multi-origin assembler: " + e.toString());
        }
    }

    /**
     * Gzip the output files and persist them to cloud storage.
     */
    private synchronized void finish() {
        LOG.info("Finished receiving data for multi-origin analysis {}", job.jobId);
        try {
            if (writeAccessibilityGrid) {
                for (int d = 0; d < nDestinationPointSets; d++) {
                    for (int p = 0; p < nPercentiles; p++) {
                        int percentile = job.templateTask.percentiles[p];
                        String destinationPointSetId = regionalAnalysis.destinationPointSetIds[d];
                        String gridFileName =
                                String.format("%s_%s_P%d.access", job.jobId, destinationPointSetId, percentile);
                        accessibilityGridWriters[d][p].finish(gridFileName);
                    }
                }
            }
            if (writeAccessibilityCsv) {
                accessibilityCsvWriter.finish(String.format("%s_access.csv.gz",job.jobId));
            }
            if (writeTimeCsv) {
                timeCsvWriter.finish(String.format("%s_times.csv.gz",job.jobId));
            }
        } catch (Exception e) {
            LOG.error("Error uploading results of multi-origin analysis {}", job.jobId, e);
        }
    }

    /**
     * There is a bit of logic in this method that wouldn't strictly need to be synchronized (the
     * dimension checks) but those should take a trivial amount of time. For safety and simplicity
     * we will synchronize the whole method. The downside is that this prevents one thread from
     * writing accessibility while another was writing travel time CSV, but this should not be
     * assumed to have any impact on performance unless measured. The writeOneValue methods are also
     * synchronized for good measure. There should be no cost to retaining the lock.
     */
    public synchronized void handleMessage (RegionalWorkResult workResult) {
        try {
            if (writeAccessibilityGrid || writeAccessibilityCsv) {
                // Sanity check the shape of the work result we received against expectations.
                checkAccessibilityDimension(workResult);
                // Infer x and y cell indexes based on the template task
                int taskNumber = workResult.taskId;
                // Drop work results for this particular origin into a little-endian output file.
                // TODO more efficient way to write little-endian integers
                // TODO check monotonic increasing invariants here rather than in worker.
                for (int d = 0; d < workResult.accessibilityValues.length; d++) {
                    int[][] percentilesForGrid = workResult.accessibilityValues[d];
                    if (writeAccessibilityCsv) {
                        String originId = originPointSet.getId(workResult.taskId);
                        // FIXME this is writing only accessibility for the first percentile and cutoff
                        accessibilityCsvWriter.writeOneValue(originId, "", percentilesForGrid[0][0]);
                    }
                    if (writeAccessibilityGrid) {
                        for (int p = 0; p < nPercentiles; p++) {
                            int[] cutoffsForPercentile = percentilesForGrid[p];
                            GridResultWriter writer = accessibilityGridWriters[d][p];
                            writer.writeOneOrigin(taskNumber, cutoffsForPercentile);
                        }
                    }
                }
            }

            if (writeTimeCsv) {
                // Sanity check the shape of the work result we received against expectations.
                checkTravelTimeDimension(workResult);
                String originId = originPointSet.getId(workResult.taskId);
                boolean oneToOne = job.templateTask.oneToOne;
                for (int p = 0; p < nPercentiles; p++) {
                    int[] percentileResult = workResult.travelTimeValues[p];
                    for (int d = 0; d < percentileResult.length; d++) {
                        int travelTime = percentileResult[d];
                        // oneToOne results will perform only one iteration of this loop
                        // Always writing both origin and destination ID we should alert the user if something is amiss.
                        int destinationIndex = oneToOne ? workResult.taskId : d;
                        String destinationId = destinationPointSet.getId(destinationIndex);
                        timeCsvWriter.writeOneValue(originId, destinationId, travelTime);
                    }
                }
            }

            // Don't double-count origins if we receive them more than once. Atomic get-and-increment requires
            // synchronization, currently achieved by synchronizing this entire method.
            if (!originsReceived.get(workResult.taskId)) {
                originsReceived.set(workResult.taskId);
                nComplete += 1;
            }
            if (nComplete == nOriginsTotal && !error) {
                finish();
            }
        } catch (Exception e) {
            error = true;
            LOG.error("Error assembling results for query {}", job.jobId, e);
        }
    }

    /**
     * Check that each dimension of the 3D results array matches the expected size for the job being processed.
     * There are different dimension requirements for accessibility and travel time results, so two different methods.
     */
    private void checkAccessibilityDimension (RegionalWorkResult workResult) {
        checkDimension(workResult, "destination pointsets", workResult.accessibilityValues.length, this.nDestinationPointSets);
        for (int[][] percentilesForGrid : workResult.accessibilityValues) {
            checkDimension(workResult, "percentiles", percentilesForGrid.length, this.nPercentiles);
            for (int[] cutoffsForPercentile : percentilesForGrid) {
                checkDimension(workResult, "cutoffs", cutoffsForPercentile.length, this.nCutoffs);
            }
        }
    }

    /**
     * Check that each dimension of the 2D results array matches the expected size for the job being processed.
     * There are different dimension requirements for accessibility and travel time results, so two different methods.
     */
    private void checkTravelTimeDimension (RegionalWorkResult workResult) {
        // In one-to-one mode, we expect only one value per origin, the destination point at the same pointset index as
        // the origin point. Otherwise, for each origin, we expect one value per destination.
        final int nDestinations = job.templateTask.oneToOne ? 1 : destinationPointSet.featureCount();
        checkDimension(workResult, "percentiles", workResult.travelTimeValues.length, nPercentiles);
        for (int[] percentileResult : workResult.travelTimeValues) {
            checkDimension(workResult, "destinations", percentileResult.length, nDestinations);
        }
    }

    /** Clean up and cancel this grid assembler, typically when a job is canceled while still being processed. */
    public synchronized void terminate () throws IOException {
        if (writeAccessibilityGrid) {
            for (GridResultWriter[] writers : accessibilityGridWriters) {
                for (GridResultWriter writer : writers) {
                    writer.terminate();
                }
            }
        }
        if (writeAccessibilityCsv) {
            accessibilityCsvWriter.terminate();
        }
        if (writeTimeCsv) {
            timeCsvWriter.terminate();
        }
    }

    /**
     * We don't have any straightforward way to return partial CSV results, so we only return
     * partially filled grids. This leaks the file object out of the abstraction so is not ideal,
     * but will work for now to allow partial display.
     */
    public File getGridBufferFile () {
        if (accessibilityGridWriters == null) {
            return null;
        } else {
            // TODO this returns only one buffer file, which has not been processed by the SelectingGridReducer
            return accessibilityGridWriters[0][0].bufferFile;
        }
    }

    /**
     * Validate that the work results we're receiving match what is expected for the job at hand.
     */
    private void checkDimension (RegionalWorkResult workResult, String dimensionName,
                                 int seen, int expected) {
        if (seen != expected) {
            LOG.error("Result for task {} of job {} has {} {}, expected {}.",
                    workResult.taskId, workResult.jobId, dimensionName, seen, expected);
            error = true;
        }
    }

}
