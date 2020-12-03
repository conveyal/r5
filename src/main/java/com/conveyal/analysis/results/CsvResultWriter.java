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
    private final int nDataColumns;

    /**
     * Construct a writer to record incoming results in a CSV file, with header row consisting of
     * "origin", "destination", and the supplied indicator.
     * FIXME it's strange we're manually passing injectable components into objects not wired up at application construction.
     */
    CsvResultWriter (RegionalTask task, String outputBucket, FileStorage fileStorage, String... dataColumns) throws IOException {
        super(fileStorage);
        super.prepare(task.jobId, outputBucket);
        this.fileName = task.jobId + dataColumns[0];
        this.nDataColumns = dataColumns.length;
        BufferedWriter bufferedWriter = new BufferedWriter(new FileWriter(bufferFile));
        csvWriter = new CsvWriter(bufferedWriter, ',');
        // Write the CSV header
        csvWriter.writeRecord(ArrayUtils.addAll(new String[]{"origin", "destination"}, dataColumns));
        LOG.info("Created csv file to store {} results from workers.", dataColumns[0]);
    }

    /**
     * Gzip the csv file and upload it to S3.
     */
    protected synchronized void finish () throws IOException {
        csvWriter.close();
        super.finish(this.fileName);
    }

    /**
     * Write a single row into the CSV file.
     */
    synchronized void writeOneValue (String originId, String destinationId, String... values) throws IOException {
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
