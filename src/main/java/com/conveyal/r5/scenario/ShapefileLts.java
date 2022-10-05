package com.conveyal.r5.scenario;

import com.conveyal.file.DataSourceException;
import com.conveyal.file.FileStorage;
import com.conveyal.file.FileStorageFormat;
import com.conveyal.file.FileStorageKey;
import com.conveyal.r5.shapefile.ShapefileMatcher;
import com.conveyal.r5.transit.TransportNetwork;
import com.conveyal.util.ExceptionUtils;
import gnu.trove.list.array.TIntArrayList;

import java.io.File;
import java.util.List;

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
    public boolean prefetchFiles (FileStorage fileStorage) {
        try {
            prefetchShapefile(fileStorage, dataSourceId);
        } catch (DataSourceException dx) {
            errors.add(ExceptionUtils.shortAndLongString(dx));
            return true;
        }
        fileStorageKey = new FileStorageKey(DATASOURCES, dataSourceId, FileStorageFormat.SHP.extension);
        localFile = fileStorage.getFile(fileStorageKey);
        return errors.size() > 0;
    }

    @Override
    public boolean apply (TransportNetwork network) {
        // Replicate the entire flags array so we can write to it (following copy-on-write policy).
        // Otherwise the TIntAugmentedList only allows extending the base graph.
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

    /**
     * Return a File for the .shp file with the given dataSourceId, ensuring all associated sidecar files are local.
     * Shapefiles are usually accessed using only the .shp file's name. The reading code will look for sidecar files of
     * the same name in the same filesystem directory. If only the .shp is requested from the FileStorage it will not
     * pull down any of the associated sidecar files from cloud storage. This method will ensure that they are all on
     * the local filesystem before we try to read the .shp.
     */
    public static File prefetchShapefile (FileStorage fileStorage, String dataSourceId) {
        // TODO Clarify FileStorage semantics: which methods cause files to be pulled down;
        //      which methods tolerate non-existing file and how do they react?
        for (String extension : List.of("shp", "shx", "dbf", "prj")) {
            FileStorageKey subfileKey = new FileStorageKey(DATASOURCES, dataSourceId, extension);
            if (fileStorage.exists(subfileKey)) {
                fileStorage.getFile(subfileKey);
            } else {
                if (!extension.equals("shx")) {
                    String filename = String.join(".", dataSourceId, extension);
                    throw new DataSourceException("Required shapefile sub-file was not found: " + filename);
                }
            }
        }
        return fileStorage.getFile(new FileStorageKey(DATASOURCES, dataSourceId, "shp"));
    }
}
