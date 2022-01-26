package com.conveyal.r5.analyst.scenario;

import com.conveyal.analysis.components.WorkerComponents;
import com.conveyal.file.FileStorageFormat;
import com.conveyal.file.FileStorageKey;
import com.conveyal.gtfs.Geometries;
import com.conveyal.r5.common.GeometryUtils;
import com.conveyal.r5.profile.StreetMode;
import com.conveyal.r5.rastercost.ElevationCostField;
import com.conveyal.r5.rastercost.ElevationLoader;
import com.conveyal.r5.streets.EdgeStore;
import com.conveyal.r5.transit.TransportNetwork;
import com.fasterxml.jackson.annotation.JsonIgnore;
import gnu.trove.iterator.TIntIterator;
import gnu.trove.set.TIntSet;
import gnu.trove.set.hash.TIntHashSet;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Polygon;

import java.io.File;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import static com.conveyal.file.FileCategory.DATASOURCES;
import static com.conveyal.r5.labeling.LevelOfTrafficStressLabeler.intToLts;

/**
 * Custom (experimental) Modification that loads costs from a raster.
 * As a custom modification, a model class only exists in R5, there is no separate UI/backend modification class.
 */
public class RasterCost extends Modification {

    /**
     * The ID of the DataSource, which must be a geotiff raster.
     * This will define the field over which to evaluate the costs.
     * It is assumed to be of type GEOTIFF because the workers don't have access to the DataStore metadata.
     */
    public String dataSourceId;

    public enum CostFunction {
        TOBLER, SHADE
    }

    /**
     * The function that transforms a series of values along a road segment to a cost that will be added onto the
     * base street edge traversal cost.
     */
    public CostFunction costFunction;

    /**
     * Scale the raster values before passing them to the cost function. For example, the inpur raster file
     * may be in decimeters to make it more compact with less artificial precision.
     */
    public double inputScale = 1;

    /**
     * Scale the cost after applying the function rather than the input,
     * since cost functions are not necessarily linear.
     */
    public double outputScale = 1;

    private FileStorageKey fileStorageKey;

    private File localFile;

    ElevationLoader loader;

    @Override
    public boolean resolve (TransportNetwork network) {
        // Check that the ID exists, and maybe by how much it overlaps the street network (what percentage of streets).
        fileStorageKey = new FileStorageKey(DATASOURCES, dataSourceId, FileStorageFormat.GEOTIFF.extension);
        if (!WorkerComponents.fileStorage.exists(fileStorageKey)) {
            errors.add("No Data Source exists with key: " + fileStorageKey.toString());
        } else {
            localFile = WorkerComponents.fileStorage.getFile(fileStorageKey);
            // Workers don't have a database connection so we can't examine the DataSource metadata.
            // Just open it to determine whether it's the right type, by creating the laader.
            loader = ElevationLoader.forFile(localFile);
            if (loader == null) {
                errors.add(String.format("Could not read grid coverage from %s.", localFile));
            }
            // info.add(String.format("Will affect %d edges out of %d candidates.", 1, 2));
        }
        return errors.size() > 0;
    }

    @Override
    public boolean apply (TransportNetwork network) {
        ElevationCostField costField = loader.load(network.streetLayer);
        EdgeStore edgeStore = network.streetLayer.edgeStore;
        if (edgeStore.costFields == null) {
            edgeStore.costFields = new ArrayList<>();
        }
        edgeStore.costFields.add(costField);
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
        return 90;
    }

}
