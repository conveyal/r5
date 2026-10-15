package com.conveyal.data.census;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.stream.Stream;

/**
 * Import data from the US Census into a seamless store on disk.
 */
public class CensusLoader {
    protected static final Logger LOG = LoggerFactory.getLogger(CensusLoader.class);

    public static void main (String... args) throws Exception {
        if (args.length != 1) {
            System.err.println("usage: CensusLoader data_dir");
            return;
        }
        File indir = new File(args[0]);
        ShapeDataStore store = load(indir);
        store.writeTiles(new File(indir, "tiles"));
        store.close();
    }

    /**
     * Load TIGER geometries and LODES workforce and jobs data from the tiger, workforce and jobs subdirectories of
     * the given directory. The caller is responsible for writing out and closing the returned store.
     */
    public static ShapeDataStore load (File indir) {
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
        return store;
    }
}
