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
import gnu.trove.list.array.TIntArrayList;

import java.io.File;

import static com.conveyal.file.FileCategory.DATASOURCES;

/**
 * Custom (experimental) Modification that loads LTS values from a shapefile and matches them to the street edges.
 * As a custom modification, a model class only exists in R5, there is no separate UI/backend modification class.
 *
 * TODO This modification is not effective together with transit - it doesn't cause the egress tables to be rebuilt.
 * Hmm, actually we don't ever recompute bike egress. Changing the LTS value does not invalidate bike egress tables.
 */
public class ShapefileLts extends Modification {

    public String ltsDataSource;

    /**
     * ID of the linear shapefile DataSource containing bicycle LTS to be matched to streets.
     * We must assume its type because the workers don't have access to the DataStore metadata.
     */
    public String dataSourceId;

    /** The name of the numeric attribute within the ltsDataSource containing LTS values from 1-4. */
    public String ltsAttribute = "lts";

    private FileStorageKey fileStorageKey;

    private File localFile;

    @Override
    public boolean resolve (TransportNetwork network) {
        try {
            TransportNetworkCache.prefetchShapefile(WorkerComponents.fileStorage, dataSourceId);
        } catch (DataSourceException dx) {
           addError(ExceptionUtils.shortAndLongString(dx));
            return true;
        }
        fileStorageKey = new FileStorageKey(DATASOURCES, dataSourceId, FileStorageFormat.SHP.extension);
        localFile = WorkerComponents.fileStorage.getFile(fileStorageKey);
        return hasErrors();
    }

    @Override
    public boolean apply (TransportNetwork network) {
        // Replicate the entire flags array so we can write to it (following copy-on-write policy).
        // Otherwise the TIntAugmentedList only allows extending the base graph. An alternative approach can be seen in
        // ModifyStreets, where all affected edges are marked deleted and then recreated in the augmented lists.
        // The appraoch here assumes a high percentage of edges changed, while ModifyStreets assumes a small percentage.
        network.streetLayer.edgeStore.flags = new TIntArrayList(network.streetLayer.edgeStore.flags);
        ShapefileMatcher shapefileMatcher = new ShapefileMatcher(network.streetLayer);
        try {
            shapefileMatcher.match(localFile.getAbsolutePath(), ltsAttribute);
        } catch (Exception e) {
           addError(ExceptionUtils.shortAndLongString(e));
        }
        return hasErrors();
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
