package com.conveyal.analysis.results;

import com.conveyal.file.FileStorage;
import com.conveyal.r5.analyst.cluster.RegionalTask;
import com.csvreader.CsvWriter;
import com.google.common.base.Preconditions;
import org.apache.commons.lang3.ArrayUtils;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;

/**
 * Write regional analysis results arriving from workers into a CSV file. This is the output format
 * for origin/destination "skim" matrices, or for accessibility indicators from non-gridded
 * ("freeform") origin point sets.
 */
public class CsvResultWriter extends ResultWriter {

    private final CsvWriter csvWriter;
    private final String fileName;
    private int nDataColumns;
    private Result resultType;

    public enum Result {
        ACCESS,
        TIMES,
        PATHS
    }

    /**
     * Construct a writer to record incoming results in a CSV file, with header row consisting of
     * "origin", "destination", and the supplied indicator.
     * FIXME it's strange we're manually passing injectable components into objects not wired up at application construction.
     */
    CsvResultWriter (RegionalTask task, String outputBucket, FileStorage fileStorage, Result resultType) throws IOException {
        super(fileStorage);
        super.prepare(task.jobId, outputBucket);
        this.resultType = resultType;
        this.fileName = task.jobId + "_" + resultType +".csv";
        BufferedWriter bufferedWriter = new BufferedWriter(new FileWriter(bufferFile));
        csvWriter = new CsvWriter(bufferedWriter, ',');
    }

    /**
     * Writes a header row, including "origin," "destination," and the supplied data columns
     */
    public void setDataColumns(String... columns) throws IOException {
        this.nDataColumns = columns.length;
        csvWriter.writeRecord(ArrayUtils.addAll(new String[]{"origin", "destination"}, columns));
        LOG.info("Created csv file to store {} results from workers.", resultType);
    }

    /**
     * Gzip the csv file and upload it to S3.
     */
    protected synchronized void finish () throws IOException {
        csvWriter.close();
        super.finish(this.fileName + ".gz");
    }

    /**
     * Write a single row into the CSV file.
     */
    synchronized void writeOneRow(String originId, String destinationId, String... values) throws IOException {
        // CsvWriter is not threadsafe and multiple threads may call this, so the actual writing is synchronized (TODO confirm)
        Preconditions.checkArgument(values.length == nDataColumns, "Attempted to write the wrong number of columns to" +
                " a result CSV");
        synchronized (this) {
            csvWriter.writeRecord(ArrayUtils.addAll(new String[]{originId, destinationId}, values));
        }
    }

    @Override
    synchronized void terminate () throws IOException {
        csvWriter.close();
        bufferFile.delete();
    }

}
