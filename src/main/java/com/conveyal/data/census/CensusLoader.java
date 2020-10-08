package com.conveyal.data.census;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.stream.Stream;

/**
 * Import data from the US Census into a seamless store in S3 or on disk.
 */
public class CensusLoader {
    protected static final Logger LOG = LoggerFactory.getLogger(CensusLoader.class);

    public static void main (String... args) throws Exception {
        File indir = new File(args[0]);
        File tiger = new File(indir, "tiger");

        ShapeDataStore store = new ShapeDataStore();

        // load up the tiger files in parallel
        LOG.info("Loading TIGER (geometry)");
        Stream.of(tiger.listFiles())
            .filter(f -> f.getName().endsWith(".shp"))
            .forEach(f -> {
                LOG.info("Loading file {}", f);
                TigerLineSource src = new TigerLineSource(f);
                try {
                    src.load(store);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

        LOG.info("TIGER done");

        LOG.info("Loading LODES workforce data");
        File workforce = new File(indir, "workforce");
        Stream.of(workforce.listFiles())
                .filter(f -> f.getName().endsWith(".csv.gz"))
                .forEach(f -> {
                    LOG.info("Loading file {}", f);
                    try {
                        new LodesSource(f, LodesSource.LodesType.RESIDENCE).load(store);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
        LOG.info("Workforce done");

        LOG.info("Loading LODES jobs data");
        File jobs = new File(indir, "jobs");
        Stream.of(jobs.listFiles())
                .filter(f -> f.getName().endsWith(".csv.gz"))
                .forEach(f -> {
                    LOG.info("Loading file {}", f);
                    try {
                        new LodesSource(f, LodesSource.LodesType.WORKPLACE).load(store);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
        LOG.info("Jobs done");

        if (args.length == 1)
            store.writeTiles(new File(indir, "tiles"));
        else
            // write to s3
            store.writeTilesToS3(args[1]);

        store.close();
    }
}
