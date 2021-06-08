package com.conveyal.analysis.results;

import com.conveyal.analysis.AnalysisServerException;
import com.conveyal.analysis.components.broker.Job;
import com.conveyal.analysis.models.RegionalAnalysis;
import com.conveyal.analysis.persistence.Persistence;
import com.conveyal.file.FileStorage;
import com.conveyal.file.FileStorageFormat;
import com.conveyal.r5.analyst.PointSet;
import com.conveyal.r5.analyst.cluster.RegionalWorkResult;
import com.conveyal.r5.util.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

import static com.conveyal.r5.common.Util.notNullOrEmpty;
import static com.google.common.base.Preconditions.checkArgument;

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

    // One writer per CSV/Grids we're outputting
    private List<RegionalResultWriter> resultWriters = new ArrayList<>();

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

    /**
     * Constructor. This sets up one or more ResultWriters depending on whether we're writing gridded or non-gridded
     * cumulative opportunities accessibility, or origin-destination travel times.
     * TODO do not pass the FileStorage component down into this non-component and the ResultWriter non-component,
     *      clarify design concepts on this point (e.g. only components should know other components exist).
     *      Rather than pushing the component all the way down to the leaf function call, we return the finished
     *      file up to an umbrella location where a single reference to the file storage can be used to
     *      store all of them.
     */
    public MultiOriginAssembler (RegionalAnalysis regionalAnalysis, Job job, FileStorage fileStorage) {
        try {
            this.regionalAnalysis = regionalAnalysis;
            this.job = job;
            this.nOriginsTotal = job.nTasksTotal;
            this.originsReceived = new BitSet(job.nTasksTotal);
            // Check that origin and destination sets are not too big for generating CSV files.
            if (!job.templateTask.makeTauiSite &&
                 job.templateTask.destinationPointSetKeys[0].endsWith(FileStorageFormat.FREEFORM.extension)
            ) {
               // This requires us to have already loaded this destination pointset instance into the transient field.
                PointSet destinationPointSet = job.templateTask.destinationPointSets[0];
                if ((job.templateTask.recordTimes || job.templateTask.includePathResults) && !job.templateTask.oneToOne) {
                    if (nOriginsTotal * destinationPointSet.featureCount() > MAX_FREEFORM_OD_PAIRS ||
                        destinationPointSet.featureCount() > MAX_FREEFORM_DESTINATIONS
                    ) {
                        throw new AnalysisServerException(String.format(
                            "Freeform requests limited to %d destinations and %d origin-destination pairs.",
                            MAX_FREEFORM_DESTINATIONS, MAX_FREEFORM_OD_PAIRS
                        ));
                    }
                }
            }

            if (job.templateTask.recordAccessibility) {
                if (job.templateTask.originPointSet != null) {
                    resultWriters.add(new AccessCsvResultWriter(job.templateTask, fileStorage));
                } else {
                    resultWriters.add( new MultiGridResultWriter(regionalAnalysis, job.templateTask, fileStorage));
                }
            }

            if (job.templateTask.recordTimes) {
                resultWriters.add(new TimeCsvResultWriter(job.templateTask, fileStorage));
            }

            if (job.templateTask.includePathResults) {
                resultWriters.add(new PathCsvResultWriter(job.templateTask, fileStorage));
            }

            checkArgument(job.templateTask.makeTauiSite || notNullOrEmpty(resultWriters),
                "A non-Taui regional analysis should always create at least one grid or CSV file.");

            // Record the paths of any CSV files that will be produced by this analysis.
            // The caller must flush the RegionalAnalysis back out to the database to retain this information.
            // We avoid database access here in constructors, especially when called in synchronized methods.
            for (RegionalResultWriter writer : resultWriters) {
                // FIXME instanceof+cast is ugly, do this some other way or even record the Grids
                if (writer instanceof CsvResultWriter) {
                    CsvResultWriter csvWriter = (CsvResultWriter) writer;
                    regionalAnalysis.resultStorage.put(csvWriter.resultType(), csvWriter.fileName);
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Exception while creating multi-origin assembler: " + ExceptionUtils.stackTraceString(e));
        }
    }

    /**
     * Gzip the output files and persist them to cloud storage.
     */
    private synchronized void finish() {
        LOG.info("Finished receiving data for multi-origin analysis {}", job.jobId);
        try {
            for (RegionalResultWriter writer : resultWriters) {
                writer.finish();
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
     * assumed to have any impact on performance unless measured. The writeOneValue methods are also synchronized
     * for good measure. There should be no additional cost to retaining the lock when entering those methods.
     */
    public synchronized void handleMessage (RegionalWorkResult workResult) throws Exception {
        for (RegionalResultWriter writer : resultWriters) {
            writer.writeOneWorkResult(workResult);
        }
        // Don't double-count origins if we receive them more than once. Atomic get-and-increment requires
        // synchronization, currently achieved by synchronizing this entire method.
        if (!originsReceived.get(workResult.taskId)) {
            originsReceived.set(workResult.taskId);
            nComplete += 1;
        }
        if (nComplete == nOriginsTotal) {
            finish();
        }
    }

    /** Clean up and cancel this grid assembler, typically when a job is canceled while still being processed. */
    public synchronized void terminate () throws Exception {
        for (RegionalResultWriter writer : resultWriters) {
            writer.terminate();
        }
    }

}
