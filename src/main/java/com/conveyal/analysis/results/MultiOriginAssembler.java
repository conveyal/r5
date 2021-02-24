package com.conveyal.analysis.results;

import com.conveyal.analysis.AnalysisServerException;
import com.conveyal.analysis.components.broker.Job;
import com.conveyal.analysis.models.RegionalAnalysis;
import com.conveyal.analysis.persistence.Persistence;
import com.conveyal.file.FileStorage;
import com.conveyal.file.FileStorageFormat;
import com.conveyal.r5.analyst.PointSet;
import com.conveyal.r5.analyst.cluster.RegionalWorkResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.BitSet;

/**
 * This assembles regional results arriving from workers into one or more files per regional analysis on
 * the backend. This is not a singleton component: one MultiOriginAssembler instance is created per currently active
 * job awaiting results from workers. It delegates to ResultWriters to actually slot results into different file formats.
 */
public class MultiOriginAssembler {

    public static final Logger LOG = LoggerFactory.getLogger(MultiOriginAssembler.class);

    private static final int MAX_FREEFORM_OD_PAIRS = 16_000_000;

    private static final int MAX_FREEFORM_DESTINATIONS = 4_000_000;

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

    // TODO the grid/CSV ResultWriters could potentially be replaced with a combined list and polymorphism e.g. for
    //  (ResultWriter rw : resultWriters) rw.writeOne(RegionalWorkResult workResult);
    private ArrayList<CsvResultWriter> csvResultWriters = new ArrayList<>();

    /** For the time being this field is only set when the origins are freeform (rather than a grid). */
    private PointSet originPointSet;

    /** For the time being this field is only set when the destinations are freeform (rather than a grid). */
    private PointSet destinationPointSet;

    /** TODO check if error is true before all results are received (when receiving each result?) and cancel job. */
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
        this.originPointSet = job.templateTask.originPointSet;
        try {
            if (job.templateTask.recordAccessibility) {
                if (job.templateTask.originPointSet != null) {
                    LOG.info(
                        "Creating CSV file to store accessibility results for {} origins.",
                        job.nTasksTotal
                    );
                    csvResultWriters.add(new AccessCsvResultWriter(job.templateTask, outputBucket, fileStorage));
                } else {
                    // Create one grid writer per percentile and destination pointset
                    accessibilityGridWriters = new GridResultWriter[nDestinationPointSets][nPercentiles];
                    for (int d = 0; d < nDestinationPointSets; d++) {
                        for (int p = 0; p < nPercentiles; p++) {
                            accessibilityGridWriters[d][p] =
                                    new GridResultWriter(job.templateTask, outputBucket, fileStorage);
                        }
                    }
                }
            }

            if (!job.templateTask.makeTauiSite &&
                 job.templateTask.destinationPointSetKeys[0].endsWith(FileStorageFormat.FREEFORM.extension)
            ) {
                // This requires us to have already loaded this destination pointset instance into the transient field.
                destinationPointSet = job.templateTask.destinationPointSets[0];
                if ((job.templateTask.recordTimes || job.templateTask.includePathResults) && !job.templateTask.oneToOne) {
                    if (nOriginsTotal * destinationPointSet.featureCount() > MAX_FREEFORM_OD_PAIRS ||
                        destinationPointSet.featureCount() > MAX_FREEFORM_DESTINATIONS
                    ) {
                        // TODO actually we need to check dimensions for gridded pointsets as well,
                        //  which can be supplied for recordTimes.
                        error = true;
                        throw new AnalysisServerException(String.format(
                            "Freeform requests limited to %d destinations and %d origin-destination pairs.",
                            MAX_FREEFORM_DESTINATIONS, MAX_FREEFORM_OD_PAIRS
                        ));
                    }
                }
            }

            if (job.templateTask.recordTimes) {
                csvResultWriters.add(new TimeCsvResultWriter(job.templateTask, outputBucket, fileStorage));
            }

            if (job.templateTask.includePathResults) {
                csvResultWriters.add(new PathCsvResultWriter(job.templateTask, outputBucket, fileStorage));
            }

            {
                int nWriters = csvResultWriters.size();
                if (accessibilityGridWriters != null) {
                    for (GridResultWriter[] grw : accessibilityGridWriters) {
                        nWriters += grw.length;
                    }
                }
                if (nWriters == 0) {
                    // TODO handle all error conditions of this form with a single method that also cancels the job
                    error = true;
                    LOG.error("A regional analysis should always create at least one grid or CSV file.");
                }
            }

            // Record the paths of any CSV files that will be produced by this analysis.
            // The caller must flush the RegionalAnalysis back out to the database to retain this information.
            // We avoid database access here in constructors, especially when called in synchronized methods.
            for (CsvResultWriter writer : csvResultWriters) {
                regionalAnalysis.addCsvStoragePath(writer.resultType());
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
            if (accessibilityGridWriters != null) {
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
            for (CsvResultWriter csvWriter : csvResultWriters) {
                csvWriter.finish();
            }
            regionalAnalysis.complete = true;
            // Write updated regionalAnalysis object back out to database, to mark it complete and record locations
            // of any CSV files generated. Use method that updates lock/timestamp, otherwise updates are not seen in UI.
            // TODO verify whether there is a reason to use regionalAnalyses.modifyWithoutUpdatingLock().
            Persistence.regionalAnalyses.put(regionalAnalysis);
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
            // TODO replace individual calls to writeOneWorkResult() with iteration over the list. Eliminate fields.

            if (job.templateTask.recordAccessibility && accessibilityGridWriters != null) {
                // Drop work results for this particular origin into a little-endian output file.
                // TODO more efficient way to write little-endian integers
                // TODO check monotonic increasing invariants here rather than in worker.
                // Infer x and y cell indexes based on the template task
                int taskNumber = workResult.taskId;
                for (int d = 0; d < workResult.accessibilityValues.length; d++) {
                    int[][] percentilesForGrid = workResult.accessibilityValues[d];
                    for (int p = 0; p < nPercentiles; p++) {
                        int[] cutoffsForPercentile = percentilesForGrid[p];
                        GridResultWriter gridWriter = accessibilityGridWriters[d][p];
                        gridWriter.writeOneOrigin(taskNumber, cutoffsForPercentile);
                    }
                }
            }

            if (csvResultWriters != null) {
                for (CsvResultWriter csvWriter : csvResultWriters) {
                    csvWriter.writeOneWorkResult(workResult);
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

    /** Clean up and cancel this grid assembler, typically when a job is canceled while still being processed. */
    public synchronized void terminate () throws IOException {
        if (accessibilityGridWriters != null) {
            for (GridResultWriter[] writers : accessibilityGridWriters) {
                for (GridResultWriter writer : writers) {
                    writer.terminate();
                }
            }
        }
        for (CsvResultWriter writer : csvResultWriters) {
            writer.terminate();
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

}
