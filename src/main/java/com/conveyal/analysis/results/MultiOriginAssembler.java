package com.conveyal.analysis.results;

import com.conveyal.analysis.components.broker.Job;
import com.conveyal.file.FileStorageKey;
import com.conveyal.r5.analyst.cluster.RegionalWorkResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * This assembles regional results arriving from workers into one or more files per regional analysis on
 * the backend. This is not a singleton component: one MultiOriginAssembler instance is created per currently active
 * job awaiting results from workers. It delegates to ResultWriters to actually slot results into different file formats.
 */
public class MultiOriginAssembler {

    public static final Logger LOG = LoggerFactory.getLogger(MultiOriginAssembler.class);

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
     * There is a bit of logic in this method that wouldn't strictly need to be synchronized (the dimension checks) but
     * those should take a trivial amount of time. For safety and simplicity we synchronize the whole method. The
     * downside is that this prevents one thread from writing accessibility while another was writing travel time CSV,
     * but this should not be assumed to have any impact on performance unless measured. The writeOneValue methods on
     * this class are also synchronized for good measure. There should be no additional cost to retaining the lock when
     * entering those methods.
     */
    public synchronized Map<FileStorageKey, File> handleMessage (RegionalWorkResult workResult) throws Exception {
        var resultFiles = new HashMap<FileStorageKey, File>();
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
                    var result = writer.finish();
                    resultFiles.put(result.getKey(), result.getValue());
                }
            } catch (Exception e) {
                LOG.error("Error uploading results of multi-origin analysis {}", job.jobId, e);
            }
        }
        return resultFiles;
    }

    /** Clean up and cancel this grid assembler, typically when a job is canceled while still being processed. */
    public synchronized void terminate () throws Exception {
        for (RegionalResultWriter writer : resultWriters) {
            writer.terminate();
        }
    }

}
