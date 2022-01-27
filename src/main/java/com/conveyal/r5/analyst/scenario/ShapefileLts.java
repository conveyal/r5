package com.conveyal.r5.analyst.scenario;

import com.conveyal.analysis.components.WorkerComponents;
import com.conveyal.analysis.datasource.DataSourceException;
import com.conveyal.file.FileStorageFormat;
import com.conveyal.file.FileStorageKey;
import com.conveyal.r5.rastercost.ElevationLoader;
import com.conveyal.r5.shapefile.ShapefileMatcher;
import com.conveyal.r5.transit.TransportNetwork;
import com.conveyal.r5.transit.TransportNetworkCache;
import com.conveyal.r5.util.ExceptionUtils;
import gnu.trove.list.TIntList;
import gnu.trove.list.array.TIntArrayList;

import java.io.File;

import static com.conveyal.file.FileCategory.DATASOURCES;

/**
 * Custom (experimental) Modification that loads LTS values from a shapefile and matches them to the street edges.
 * As a custom modification, a model class only exists in R5, there is no separate UI/backend modification class.
 */
public class ShapefileLts extends Modification {

    /**
     * The ID of the DataSource, which must be a Shapefile.
     * We must assume its type because the workers don't have access to the DataStore metadata.
     */
    public String dataSourceId;

    public String ltsAttribute = "lts_ov";

    private FileStorageKey fileStorageKey;

    private File localFile;

    ElevationLoader loader;

    @Override
    public boolean resolve (TransportNetwork network) {
        try {
            TransportNetworkCache.prefetchShapefile(WorkerComponents.fileStorage, dataSourceId);
        } catch (DataSourceException dx) {
            errors.add(ExceptionUtils.shortAndLongString(dx));
            return true;
        }
        fileStorageKey = new FileStorageKey(DATASOURCES, dataSourceId, FileStorageFormat.SHP.extension);
        localFile = WorkerComponents.fileStorage.getFile(fileStorageKey);
        return errors.size() > 0;
    }

    @Override
    public boolean apply (TransportNetwork network) {
        // Replicate the entire flags array so we can write to it.
        // Otherwise it's just extending the base graph and writing will fail.
        // FIXME: TIntAugmentedList does not implement the iterator needed for a copy operation
        network.streetLayer.edgeStore.flags = new TIntArrayList(network.streetLayer.edgeStore.flags);
        ShapefileMatcher shapefileMatcher = new ShapefileMatcher(network.streetLayer);
        try {
            shapefileMatcher.match(localFile.getAbsolutePath(), ltsAttribute);
        } catch (Exception e) {
            errors.add(ExceptionUtils.shortAndLongString(e));
        }
        return errors.size() > 0;
    }

    @Override
    public boolean affectsStreetLayer () {
        return true;
    }

    @Override
    public boolean affectsTransitLayer () {
        return false;
    }

    @Override
    public int getSortOrder () {
        return 80;
    }

}
