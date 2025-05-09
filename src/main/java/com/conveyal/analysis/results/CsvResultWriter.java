package com.conveyal.analysis.results;

import com.conveyal.file.FileStorage;
import com.conveyal.r5.analyst.cluster.RegionalTask;
import com.conveyal.r5.analyst.cluster.RegionalWorkResult;
import com.csvreader.CsvWriter;
import com.google.common.base.Preconditions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;

/**
 * Common supertype of classes that write information from incoming regional work results out into CSV files.
 * Subclasses are used to record origin/destination "skim" matrices, accessibility indicators for non-gridded
 * ("freeform") origin point sets, and cataloging paths between pairs of origins and destinations.
 */
public abstract class CsvResultWriter extends BaseResultWriter implements RegionalResultWriter {

    private static final Logger LOG = LoggerFactory.getLogger(CsvResultWriter.class);

    private final CsvWriter csvWriter;
    private int nDataColumns;

    /**
     * Keep a reference to the task, for fetching percentiles, cutoffs, etc. in iteration over multidimensional results.
     * We could copy only the fields we need, but we end up copying six of them to identically named fields.
     */
    protected final RegionalTask task;

    /**
     * An Enum (usable as String) identifying the kind of results recorded by this CSV writer. This also serves as a
     * filename suffix to distinguish between different CSVs generated by a single regional analysis.
     */
    public final CsvResultType resultType;

    /**
     * Override to provide column names for this CSV writer.
     * NOTE: Due to Java weirdness, subclass implementations of this method will be called by the CsvResultWriter
     * constructor at a time when fields of the subclass remain initialized, but uninitialized final primitive
     * fields are still readable! Do not read subclass fields in these implementations until/unless this is restructured.
     */
    protected abstract String[] columnHeaders ();

    /** Override to extract row values from a single origin result. */
    protected abstract Iterable<String[]> rowValues (RegionalWorkResult workResult);

    /** Override to check the size of incoming results and ensure they match expected dimensions. */
    protected abstract void checkDimension (RegionalWorkResult workResult);

    /**
     * Construct a writer to record incoming results in a CSV file, with header row consisting of
     * "origin", "destination", and the supplied indicator.
     * FIXME it's strange we're manually passing injectable components into objects not wired up at application construction.
     */
    CsvResultWriter(RegionalTask task, CsvResultType resultType, FileStorage fileStorage) throws IOException {
        super(task.jobId + "_" + resultType + ".csv", fileStorage);
        checkArgument(task.originPointSet != null, "CsvResultWriters require FreeFormPointSet origins.");
        BufferedWriter bufferedWriter = new BufferedWriter(new FileWriter(bufferFile));
        csvWriter = new CsvWriter(bufferedWriter, ',');
        this.resultType = resultType;
        this.task = task;
        setDataColumns(columnHeaders());
        LOG.info("Created CSV file to hold {} results for regional job {}", resultType, task.jobId);
    }

    /**
     * Writes a header row containing the supplied data columns.
     */
    protected void setDataColumns(String... columns) throws IOException {
        this.nDataColumns = columns.length;
        csvWriter.writeRecord(columns);
    }

    /**
     * Gzip the csv file and move it into permanent file storage such as AWS S3.
     * Note: stored file will undergo gzip compression in super.finish(), but be stored with a .csv extension.
     * When this file is downloaded from the UI, the browser will decompress, yielding a logically named .csv file.
     * Downloads through another channel (e.g. aws s3 cp), will need to be decompressed manually.
     */
    @Override
    public synchronized void finish () throws IOException {
        csvWriter.close();
        super.finish();
    }

    /**
     * Write all rows for a single regional work result (single origin) into the CSV file.
     */
    @Override
    public void writeOneWorkResult (RegionalWorkResult workResult) throws Exception {
        // CsvWriter is not threadsafe and multiple threads may call this, so after values are generated,
        // the actual writing is synchronized (TODO confirm)
        // Is result row generation slow enough to bother synchronizing only the following block?
        // This first dimension check is specific to each subclass. The check in the loop below is more general,
        // applying to all subclasses (after the subclass-specific rowValues method may have added some columns).
        checkDimension(workResult);
        Iterable<String[]> rows = rowValues(workResult);
        synchronized (this) {
            for (String[] values : rows) {
                Preconditions.checkArgument(values.length == nDataColumns,
                        "Attempted to write the wrong number of columns to a result CSV");
                csvWriter.writeRecord(values);
            }
        }
    }

    @Override
    public synchronized void terminate () throws Exception {
        csvWriter.close();
        bufferFile.delete();
    }

    /**
     * Validate that the work results we're receiving match what is expected for the job at hand.
     */
    static void checkDimension (RegionalWorkResult workResult, String dimensionName, int seen, int expected) {
        if (seen != expected) {
            LOG.error(
                "Result for task {} of job {} has {} {}, expected {}.",
                workResult.taskId, workResult.jobId, dimensionName, seen, expected
            );
            // This should be caught up in MultiOriginAssembler and the error flag set on the regional analysis.
            throw new IllegalArgumentException("Work result had unexpected dimension.");
        }
    }

    /** Look up the ID of a destination in the pointset, considering whether we're in one-to-one mode. */
    String destinationId (int taskId, int index) {
        checkState(task.destinationPointSets.length == 1);
        // oneToOne results will have the same origin and destination IDs.
        // Always writing both should alert the user if something is amiss.
        if (task.oneToOne) {
            return task.destinationPointSets[0].getId(taskId);
        } else {
            return task.destinationPointSets[0].getId(index);
        }
    }

}
