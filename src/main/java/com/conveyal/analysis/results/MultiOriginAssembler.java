package com.conveyal.analysis.results;

import static com.conveyal.r5.common.Util.notNullOrEmpty;
import static com.google.common.base.Preconditions.checkArgument;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.conveyal.analysis.AnalysisServerException;
import com.conveyal.analysis.components.broker.Job;
import com.conveyal.analysis.models.RegionalAnalysis;
import com.conveyal.file.FileStorageKey;
import com.conveyal.r5.analyst.cluster.RegionalTask;
import com.conveyal.r5.analyst.cluster.RegionalWorkResult;

/**
 * This assembles regional results arriving from workers into one or more files per regional analysis on
 * the backend. This is not a singleton component: one MultiOriginAssembler instance is created per currently active
 * job awaiting results from workers. It delegates to ResultWriters to actually slot results into different file formats.
 */
public class MultiOriginAssembler {

    public static final Logger LOG = LoggerFactory.getLogger(MultiOriginAssembler.class);

    // One writer per CSV/Grids we're outputting
    public final Map<FileStorageKey, BaseResultWriter> resultWriters = new HashMap<>();

    /**
     * Constructor. This sets up one or more ResultWriters depending on whether we're writing gridded or non-gridded
     * cumulative opportunities accessibility, or origin-destination travel times.
     */
    public MultiOriginAssembler(RegionalAnalysis regionalAnalysis, Job job) {
        try {
            RegionalTask task = job.templateTask;

            if (task.originPointSet == null) {
                resultWriters.putAll(GridResultWriter.createGridResultWritersForTask(task, regionalAnalysis));
            } else {
                if (task.recordAccessibility) {
                    // Freeform origins - create CSV regional analysis results
                    FileStorageKey fileKey = RegionalAnalysis.getCsvResultFileKey(task.jobId, CsvResultType.ACCESS);
                    resultWriters.put(fileKey, new AccessCsvResultWriter(task));
                    regionalAnalysis.resultStorage.put(CsvResultType.ACCESS, fileKey.path);
                }

                if (task.includeTemporalDensity) {
                    FileStorageKey fileKey = RegionalAnalysis.getCsvResultFileKey(task.jobId, CsvResultType.TDENSITY);
                    resultWriters.put(fileKey, new TemporalDensityCsvResultWriter(task));
                    regionalAnalysis.resultStorage.put(CsvResultType.TDENSITY, fileKey.path);
                }
            }

            if (task.recordTimes) {
                FileStorageKey fileKey = RegionalAnalysis.getCsvResultFileKey(task.jobId, CsvResultType.TIMES);
                resultWriters.put(fileKey, new TimeCsvResultWriter(task));
                regionalAnalysis.resultStorage.put(CsvResultType.TIMES, fileKey.path);
            }

            if (task.includePathResults) {
                FileStorageKey fileKey = RegionalAnalysis.getCsvResultFileKey(task.jobId, CsvResultType.PATHS);
                resultWriters.put(fileKey, new PathCsvResultWriter(task));
                regionalAnalysis.resultStorage.put(CsvResultType.PATHS, fileKey.path);
            }

            checkArgument(task.makeTauiSite || notNullOrEmpty(resultWriters),
                "A non-Taui regional analysis should always create at least one grid or CSV file.");
        } catch (AnalysisServerException e) {
            throw e;
        } catch (Exception e) {
            // Handle any obscure problems we don't want end users to see without context of MultiOriginAssembler.
            throw new RuntimeException("Exception while creating multi-origin assembler: " + e.toString(), e);
        }
    }

    /**
     * Clean up the buffer files and return the files.
     */
    public synchronized Map<FileStorageKey, File> finish () throws IOException {
        Map<FileStorageKey, File> files = new HashMap<>();
        for (Map.Entry<FileStorageKey, BaseResultWriter> entry : resultWriters.entrySet()) {
            files.put(entry.getKey(), entry.getValue().finish());
        }
        return files;
    }

    /**
     * There is a bit of logic in this method that wouldn't strictly need to be synchronized (the dimension checks) but
     * those should take a trivial amount of time. For safety and simplicity we synchronize the whole method. The
     * downside is that this prevents one thread from writing accessibility while another was writing travel time CSV,
     * but this should not be assumed to have any impact on performance unless measured. The writeOneValue methods on
     * this class are also synchronized for good measure. There should be no additional cost to retaining the lock when
     * entering those methods.
     */
    public synchronized void handleMessage(RegionalWorkResult workResult) throws Exception {
        for (BaseResultWriter writer : resultWriters.values()) {
            writer.writeOneWorkResult(workResult);
        }
    }

    /** Clean up and cancel this grid assembler, typically when a job is canceled while still being processed. */
    public synchronized void terminate () throws Exception {
        for (BaseResultWriter writer : resultWriters.values()) {
            writer.terminate();
        }
    }
}
