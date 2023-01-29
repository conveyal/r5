package com.conveyal.analysis.results;

import com.conveyal.analysis.AnalysisServerException;
import com.conveyal.analysis.components.broker.Job;
import com.conveyal.r5.analyst.PointSet;
import com.conveyal.r5.analyst.cluster.PathResult;
import com.conveyal.r5.analyst.cluster.RegionalTask;
import com.conveyal.r5.analyst.cluster.RegionalWorkResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.BitSet;
import java.util.List;

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
     * The object representing the progress of the regional analysis as tracked by the broker.
     * It may appear job.templateTask has all the information needed, making the regionalAnalysis field
     * unnecessary. But the templateTask is for worker consumption, while the regionalAnalysis has fields that are more
     * readily usable in the backend assembler (e.g. destination pointset id, instead of full storage key).
     */
    public final Job job;

    // One writer per CSV/Grids we're outputting
    private final List<RegionalResultWriter> resultWriters;

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
     * Constructor. This sets up one or more ResultWriters depending on whether we're writing gridded or non-gridded
     * cumulative opportunities accessibility, or origin-destination travel times.
     */
    public MultiOriginAssembler (Job job, List<RegionalResultWriter> resultWriters) {
        this.job = job;
        this.resultWriters = resultWriters;
        this.originsReceived = new BitSet(job.nTasksTotal);
    }

    /**
     * Check that origin and destination sets are not too big for generating CSV files.
     */
    public static void ensureOdPairsUnderLimit(RegionalTask task, PointSet destinationPointSet) {
        // This requires us to have already loaded this destination pointset instance into the transient field.
        if ((task.recordTimes || task.includePathResults) && !task.oneToOne) {
            if (task.getTasksTotal() * destinationPointSet.featureCount() > MAX_FREEFORM_OD_PAIRS ||
                    destinationPointSet.featureCount() > MAX_FREEFORM_DESTINATIONS
            ) {
                throw new AnalysisServerException(String.format(
                        "Freeform requests limited to %d destinations and %d origin-destination pairs.",
                        MAX_FREEFORM_DESTINATIONS, MAX_FREEFORM_OD_PAIRS
                ));
            }
        }
    }

    /**
     * There is a bit of logic in this method that wouldn't strictly need to be synchronized (the dimension checks) but
     * those should take a trivial amount of time. For safety and simplicity we synchronize the whole method. The
     * downside is that this prevents one thread from writing accessibility while another was writing travel time CSV,
     * but this should not be assumed to have any impact on performance unless measured. The writeOneValue methods on
     * this class are also synchronized for good measure. There should be no additional cost to retaining the lock when
     * entering those methods.
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

        // If finished, run finish on all the result writers.
        if (nComplete == job.nTasksTotal) {
            LOG.info("Finished receiving data for multi-origin analysis {}", job.jobId);
            try {
                for (RegionalResultWriter writer : resultWriters) {
                    writer.finish();
                }
            } catch (Exception e) {
                LOG.error("Error uploading results of multi-origin analysis {}", job.jobId, e);
            }
        }
    }

    /** Clean up and cancel this grid assembler, typically when a job is canceled while still being processed. */
    public synchronized void terminate () throws Exception {
        for (RegionalResultWriter writer : resultWriters) {
            writer.terminate();
        }
    }

}
