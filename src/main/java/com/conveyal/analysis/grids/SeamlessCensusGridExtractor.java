package com.conveyal.analysis.grids;

import com.conveyal.analysis.models.Bounds;
import com.conveyal.data.census.S3SeamlessSource;
import com.conveyal.data.geobuf.GeobufFeature;
import com.conveyal.r5.analyst.Grid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.conveyal.analysis.models.OpportunityDataset.ZOOM;

/**
 * Fetch data from the seamless-census s3 buckets and convert it from block-level vector data (polygons)
 * to raster opportunity density data (grids).
 */
public class SeamlessCensusGridExtractor {

    private static final Logger LOG = LoggerFactory.getLogger(SeamlessCensusGridExtractor.class);

    private static final Set<String> ignoreKeys = new HashSet<>(Arrays.asList(
            "Jobs Data creation date",
            "Workers Data creation date"
    ));

    public interface Config {
        String seamlessCensusRegion ();
        String seamlessCensusBucket ();
    }

    private static S3SeamlessSource source;

    // TODO make this into a non-static Component
    public static void configureStatically (Config config) {
        source = new S3SeamlessSource(config.seamlessCensusRegion(), config.seamlessCensusBucket());
    }

    /**
     * Retrieve data for bounds and save to a bucket under a given key
     */
    public static List<Grid> retrieveAndExtractCensusDataForBounds (Bounds bounds) throws IOException {
        long startTime = System.currentTimeMillis();

        // All the features are buffered in a Map in memory. This could be problematic on large areas.
        Map<Long, GeobufFeature> features = source.extract(bounds.north, bounds.east, bounds.south, bounds.west, false);

        if (features.isEmpty()) {
            LOG.info("No seamless census data found here, not pre-populating grids");
            return new ArrayList<>();
        }

        // One string naming each attribute (column) in the incoming census data.
        Map<String, Grid> grids = new HashMap<>();
        for (GeobufFeature feature : features.values()) {
            List weights = null;
            // Iterate over all of the features
            for (Map.Entry<String, Object> e : feature.properties.entrySet()) {
                String key = e.getKey();
                if (ignoreKeys.contains(key)) continue;
                if (!(e.getValue() instanceof Number)) continue;
                Number value = (Number) e.getValue();
                // Note, the following is assuming each property has a unique name.
                Grid grid = grids.get(key);
                if (grid == null) {
                    grid = new Grid(ZOOM, bounds.envelope());
                    grid.name = key;
                    grids.put(key, grid);
                }
                if (weights == null) {
                    weights = grid.getPixelWeights(feature.geometry);
                }
                grid.incrementFromPixelWeights(weights, value.doubleValue());
            }
        }

        long endTime = System.currentTimeMillis();
        LOG.info("Extracting Census data took {} seconds", (endTime - startTime) / 1000);

        return new ArrayList<>(grids.values());
    }
}
