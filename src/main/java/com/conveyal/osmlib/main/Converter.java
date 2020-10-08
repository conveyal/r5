package com.conveyal.osmlib.main;

import com.conveyal.osmlib.OSMEntitySink;
import com.conveyal.osmlib.OSMEntitySource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

public class Converter {

    private static final Logger LOG = LoggerFactory.getLogger(Converter.class);

    /**
     * This main method will load a file into the osm-lib representation and write it back out as a stream,
     * without using an intermediate MapDB. File types are detected from the file name extensions.
     */
    public static void main(String[] args) {

        // Get input and output file names
        if (args.length < 2) {
            System.err.println("usage: Convert input.[pbf|vex] output.[pbf|vex|txt]");
            System.exit(0);
        }
        String inputPath = args[0];
        String outputPath = args[1];

        // Pump the entities from the input file directly to the output file.
        long startTime = System.currentTimeMillis();
        try {
            OSMEntitySource source = OSMEntitySource.forFile(inputPath);
            OSMEntitySink sink = OSMEntitySink.forFile(outputPath);
            source.copyTo(sink);
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
        LOG.info("Total run time: {} sec", (System.currentTimeMillis() - startTime)/1000D);
    }

}
