package com.conveyal.analysis.results;

import com.conveyal.file.FileStorage;
import com.conveyal.r5.analyst.cluster.RegionalTask;
import com.csvreader.CsvWriter;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;

/**
 * Write regional analysis results arriving from workers into a CSV file. This is the output format
 * for origin/destination "skim" matrices, or for accessibility indicators from non-gridded
 * ("freeform") origin point sets.
 */
public class CsvResultWriter extends ResultWriter {

    private CsvWriter csvWriter;

    /**
     * Construct a writer to record incoming results in a CSV file, with header row consisting of
     * "origin", "destination", and the supplied indicator.
     * FIXME it's strange we're manually passing injectable components into objects not wired up at application construction.
     */
    CsvResultWriter (RegionalTask task, String indicator, String outputBucket, FileStorage fileStorage) throws IOException {
        super(fileStorage);
        super.prepare(task.jobId, outputBucket);
        BufferedWriter bufferedWriter = new BufferedWriter(new FileWriter(bufferFile));
        csvWriter = new CsvWriter(bufferedWriter, ',');
        // Write the CSV header
        csvWriter.writeRecord(new String[]{"origin", "destination", indicator});
        LOG.info("Created csv file to store {} results from workers.", indicator);
    }

    /**
     * Gzip the csv file and upload it to S3.
     */
    @Override
    protected synchronized void finish (String fileName) throws IOException {
        csvWriter.close();
        super.finish(fileName);
    }

    /**
     * Write a single row into the CSV file.
     */
    synchronized void writeOneValue (String originId, String destinationId, int value) throws IOException {
        // CsvWriter is not threadsafe and multiple threads may call this, so the actual writing is synchronized (TODO confirm)
        synchronized (this) {
            csvWriter.writeRecord(new String[]{originId, destinationId, String.valueOf(value)});
        }
    }

    @Override
    synchronized void terminate () throws IOException {
        csvWriter.close();
        bufferFile.delete();
    }

}
