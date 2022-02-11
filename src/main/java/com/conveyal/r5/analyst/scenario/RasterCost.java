package com.conveyal.r5.analyst.scenario;

import com.conveyal.analysis.components.WorkerComponents;
import com.conveyal.file.FileStorageFormat;
import com.conveyal.file.FileStorageKey;
import com.conveyal.r5.rastercost.CostField;
import com.conveyal.r5.rastercost.ElevationLoader;
import com.conveyal.r5.rastercost.RasterDataSourceSampler;
import com.conveyal.r5.rastercost.SunLoader;
import com.conveyal.r5.streets.EdgeStore;
import com.conveyal.r5.transit.TransportNetwork;
import org.geotools.referencing.operation.builder.LocalizationGrid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;

import static com.conveyal.file.FileCategory.DATASOURCES;
import static com.conveyal.r5.analyst.scenario.RasterCost.CostFunction.MINETTI;
import static com.conveyal.r5.analyst.scenario.RasterCost.CostFunction.SUN;
import static com.conveyal.r5.analyst.scenario.RasterCost.CostFunction.TOBLER;

/**
 * Custom (experimental) Modification that loads costs from a raster.
 * As a custom modification, a model class only exists in R5, there is no separate UI/backend modification class.
 */
public class RasterCost extends Modification {

    private static final Logger LOG = LoggerFactory.getLogger(RasterCost.class);

    /**
     * The ID of the DataSource, which must be a geotiff raster.
     * This will define the field over which to evaluate the costs.
     * It is assumed to be of type GEOTIFF because the workers don't have access to the DataStore metadata.
     */
    public String dataSourceId;

    public enum CostFunction {
        TOBLER, MINETTI, SUN
    }

    /**
     * The function that transforms a series of values along a road segment to a cost that will be added onto the
     * base street edge traversal cost.
     */
    public CostFunction costFunction;

    /**
     * Scale the raster values before passing them to the cost function. For example, the input raster file
     * may be in decimeters to make it more compact with less artificial precision.
     */
    public double inputScale = 1;

    /**
     * Scale the cost after applying the function rather than the input to calibrate cost, uniformly increasing or
     * decreasing it. Separate from inputScale since cost functions are not necessarily linear.
     */
    public double outputScale = 1;

    private CostField.Loader loader;

    @Override
    public boolean resolve (TransportNetwork network) {
        if (costFunction == TOBLER || costFunction == MINETTI) {
            loader = new ElevationLoader(dataSourceId);
        } else if (costFunction == SUN) {
            loader = new SunLoader(dataSourceId);
        } else {
            errors.add("Unrecognized cost function: " + costFunction);
        }
        return errors.size() > 0;
    }

    @Override
    public boolean apply (TransportNetwork network) {
        LOG.info("Applying {} costs from raster DataSource {}.", costFunction, dataSourceId);
        CostField costField = loader.load(network.streetLayer);
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
