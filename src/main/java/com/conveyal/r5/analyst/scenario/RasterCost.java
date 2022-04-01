package com.conveyal.r5.analyst.scenario;

import com.conveyal.r5.rastercost.CostField;
import com.conveyal.r5.rastercost.ElevationLoader;
import com.conveyal.r5.rastercost.MinettiCalculator;
import com.conveyal.r5.rastercost.SunLoader;
import com.conveyal.r5.rastercost.ToblerCalculator;
import com.conveyal.r5.streets.EdgeStore;
import com.conveyal.r5.transit.TransportNetwork;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;

import static com.conveyal.r5.analyst.scenario.RasterCost.CostFunction.MINETTI;
import static com.conveyal.r5.analyst.scenario.RasterCost.CostFunction.SUN;
import static com.conveyal.r5.analyst.scenario.RasterCost.CostFunction.TOBLER;

/**
 * Custom (experimental) Modification that loads costs from a raster.
 * As a custom modification, the model class only exists in R5, there is no separate UI/backend modification class.
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
     * decreasing it. Distinct from inputScale since cost functions are not necessarily linear.
     */
    public double outputScale = 1;

    /** @see com.conveyal.r5.rastercost.RasterDataSourceSampler#setNorthShiftMeters(double) */
    public double northShiftMeters = 0;

    /** @see com.conveyal.r5.rastercost.RasterDataSourceSampler#setEastShiftMeters(double) */
    public double eastShiftMeters = 0;

    private CostField.Loader loader;

    @Override
    public boolean resolve (TransportNetwork network) {
        if (costFunction == TOBLER) {
            loader = new ElevationLoader(dataSourceId, new ToblerCalculator());
        } else if (costFunction == MINETTI) {
            loader = new ElevationLoader(dataSourceId, new MinettiCalculator());
        } else if (costFunction == SUN) {
            loader = new SunLoader(dataSourceId);
        }
        if (loader == null) {
            errors.add("Unrecognized cost function: " + costFunction);
        } else {
            loader.setNorthShiftMeters(northShiftMeters);
            loader.setEastShiftMeters(eastShiftMeters);
            checkScaleRange(inputScale);
            checkScaleRange(outputScale);
            loader.setInputScale(inputScale);
            loader.setOutputScale(outputScale);
        }
        return errors.size() > 0;
    }

    private void checkScaleRange (double scale) {
        if (scale <= 0 || scale >= 100) {
            errors.add("Scale parameters must be positive nonzero real numbers less than 100.");
        }
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
