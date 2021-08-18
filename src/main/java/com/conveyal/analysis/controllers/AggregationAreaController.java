package com.conveyal.analysis.controllers;

import com.conveyal.analysis.AnalysisServerException;
import com.conveyal.analysis.UserPermissions;
import com.conveyal.analysis.components.TaskScheduler;
import com.conveyal.analysis.models.AggregationArea;
import com.conveyal.analysis.models.DataSource;
import com.conveyal.analysis.models.SpatialDataSource;
import com.conveyal.analysis.persistence.AnalysisCollection;
import com.conveyal.analysis.persistence.AnalysisDB;
import com.conveyal.file.FileStorage;
import com.conveyal.file.FileUtils;
import com.conveyal.r5.analyst.Grid;
import com.conveyal.r5.analyst.progress.Task;
import com.conveyal.r5.util.ShapefileReader;
import com.google.common.base.Preconditions;
import org.json.simple.JSONObject;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.operation.union.UnaryUnionOp;
import org.opengis.feature.simple.SimpleFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import spark.Request;
import spark.Response;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.zip.GZIPOutputStream;

import static com.conveyal.analysis.util.JsonUtil.toJson;
import static com.conveyal.file.FileStorageFormat.GEOJSON;
import static com.conveyal.file.FileStorageFormat.SHP;
import static com.conveyal.r5.analyst.WebMercatorGridPointSet.parseZoom;
import static com.conveyal.r5.util.ShapefileReader.GeometryType.POLYGON;
import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;

/**
 * Stores vector aggregationAreas (used to define the region of a weighted average accessibility metric).
 */
public class AggregationAreaController implements HttpController {

    private static final Logger LOG = LoggerFactory.getLogger(AggregationAreaController.class);

    /**
     * Arbitrary limit to prevent UI clutter from many aggregation  areas (e.g. if someone uploads thousands of blocks).
     * Someone might reasonably request an aggregation area for each of Chicago's 50 wards, so that's a good approximate
     * limit for now.
     */
    private static int MAX_FEATURES = 100;

    private final FileStorage fileStorage;
    private final TaskScheduler taskScheduler;
    private final AnalysisCollection<AggregationArea> aggregationAreaCollection;
    private final AnalysisCollection<SpatialDataSource> dataSourceCollection;

    public AggregationAreaController (
            FileStorage fileStorage,
            AnalysisDB database,
            TaskScheduler taskScheduler
    ) {
        this.fileStorage = fileStorage;
        this.taskScheduler = taskScheduler;
        this.aggregationAreaCollection = database.getAnalysisCollection("aggregationAreas", AggregationArea.class);
        this.dataSourceCollection = database.getAnalysisCollection("dataSources", DataSource.class);
    }

    /**
     * Create binary .grid files for aggregation (aka mask) areas, save them to S3, and persist their details.
     * @param req Must include a shapefile on which the aggregation area(s) will be based.
     *            If HTTP query parameter union is "true", features will be merged to a single aggregation area, named
     *            using the value of the "name" query parameter. If union is false or if the parameter is missing, each
     *            feature will be a separate aggregation area, named using the value for the shapefile property
     *            specified by the HTTP query parameter "nameAttribute."
     */
    private List<AggregationArea> createAggregationAreas (Request req, Response res) throws Exception {
        ArrayList<AggregationArea> aggregationAreas = new ArrayList<>();
        UserPermissions userPermissions = UserPermissions.from(req);
        String dataSourceId = req.params("dataSourceId");
        String nameProperty = req.queryParams("nameProperty");
        final int zoom = parseZoom(req.queryParams("zoom"));

        // 1. Get file from storage and read its features. =============================================================
        SpatialDataSource resource = dataSourceCollection.findById(dataSourceId);
        Preconditions.checkArgument(POLYGON.equals(resource.geometryType),
                "Only polygons can be converted to aggregation areas.");
        File sourceFile;
        List<SimpleFeature> features = null;

        if (SHP.equals(resource.fileFormat)) {
            sourceFile = fileStorage.getFile(resource.storageKey());
            ShapefileReader reader = null;
            try {
                reader = new ShapefileReader(sourceFile);
                features = reader.wgs84Stream().collect(Collectors.toList());
            } finally {
                if (reader != null) reader.close();
            }
        }

        if (GEOJSON.equals(resource.fileFormat)) {
            // TODO implement
        }

        List<SimpleFeature> finalFeatures = features;
        taskScheduler.enqueue(Task.create("Aggregation area creation: " + resource.name)
                .forUser(userPermissions)
                .setHeavy(true)
                .withWorkProduct(resource)
                .withAction(progressListener -> {
                    progressListener.beginTask("Processing request", 1);
                    Map<String, Geometry> areas = new HashMap<>();

                    if (nameProperty != null && finalFeatures.size() > MAX_FEATURES) {
                        throw AnalysisServerException.fileUpload(
                                MessageFormat.format("The uploaded shapefile has {0} features, " +
                                "which exceeds the limit of {1}", finalFeatures.size(), MAX_FEATURES)
                        );
                    }

                    if (nameProperty == null) {
                        // Union (single combined aggregation area) requested
                        List<Geometry> geometries = finalFeatures.stream().map(f ->
                                (Geometry) f.getDefaultGeometry()).collect(Collectors.toList()
                        );
                        UnaryUnionOp union = new UnaryUnionOp(geometries);
                        // Name the area using the name in the request directly
                        areas.put(resource.name, union.union());
                    } else {
                        // Don't union. Name each area by looking up its value for the name property in the request.
                        finalFeatures.forEach(f -> areas.put(
                                readProperty(f, nameProperty), (Geometry) f.getDefaultGeometry())
                        );
                    }

                    // 2. Convert to raster grids, then store them. ====================================================
                    areas.forEach((String name, Geometry geometry) -> {
                        if (geometry == null) throw new AnalysisServerException("Invalid geometry uploaded.");
                        Envelope env = geometry.getEnvelopeInternal();
                        Grid maskGrid = new Grid(zoom, env);
                        progressListener.beginTask("Creating grid for " + name, maskGrid.featureCount());

                        // Store the percentage each cell overlaps the mask, scaled as 0 to 100,000
                        List<Grid.PixelWeight> weights = maskGrid.getPixelWeights(geometry, true);
                        weights.forEach(pixel -> {
                            maskGrid.grid[pixel.x][pixel.y] = pixel.weight * 100_000;
                            progressListener.increment();
                        });

                        AggregationArea aggregationArea = AggregationArea.create(userPermissions, name)
                                .withSource(resource);

                        try {
                            File gridFile = FileUtils.createScratchFile("grid");
                            OutputStream os = new GZIPOutputStream(FileUtils.getOutputStream(gridFile));
                            maskGrid.write(os);
                            os.close();

                            aggregationAreaCollection.insert(aggregationArea);
                            aggregationAreas.add(aggregationArea);

                            fileStorage.moveIntoStorage(aggregationArea.getStorageKey(), gridFile);
                        } catch (IOException e) {
                            throw new AnalysisServerException("Error processing/uploading aggregation area");
                        }
                        progressListener.increment();
                    });
                })
        );

        return aggregationAreas;

    }

    private String readProperty (SimpleFeature feature, String propertyName) {
        try {
            return feature.getProperty(propertyName).getValue().toString();
        } catch (NullPointerException e) {
            String message = String.format("The specified property '%s' was not present on the uploaded features. " +
                    "Please verify that '%s' corresponds to a shapefile column.", propertyName, propertyName);
            throw new AnalysisServerException(message);
        }
    }

    private Collection<AggregationArea> getAggregationAreas (Request req, Response res) {
        return aggregationAreaCollection.findPermitted(
                eq("regionId", req.queryParams("regionId")), UserPermissions.from(req)
        );
    }

    private JSONObject getAggregationArea (Request req, Response res) {
        AggregationArea aggregationArea = aggregationAreaCollection.findByIdIfPermitted(
                req.params("maskId"), UserPermissions.from(req)
        );
        String url = fileStorage.getURL(aggregationArea.getStorageKey());
        JSONObject wrappedUrl = new JSONObject();
        wrappedUrl.put("url", url);
        return wrappedUrl;
    }

    @Override
    public void registerEndpoints (spark.Service sparkService) {
        sparkService.path("/api/region/", () -> {
            sparkService.get("/:regionId/aggregationArea", this::getAggregationAreas, toJson);
            sparkService.get("/:regionId/aggregationArea/:maskId", this::getAggregationArea, toJson);
            sparkService.post("/:regionId/aggregationArea/:sourceId", this::createAggregationAreas, toJson);
        });
    }

}
