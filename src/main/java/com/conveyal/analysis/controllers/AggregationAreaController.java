package com.conveyal.analysis.controllers;

import com.conveyal.analysis.AnalysisServerException;
import com.conveyal.analysis.models.AggregationArea;
import com.conveyal.analysis.persistence.Persistence;
import com.conveyal.analysis.util.HttpUtils;
import com.conveyal.file.FileStorage;
import com.conveyal.file.FileStorageKey;
import com.conveyal.file.FileUtils;
import com.conveyal.r5.analyst.Grid;
import com.conveyal.r5.util.ShapefileReader;
import com.google.common.io.Files;
import com.mongodb.QueryBuilder;
import org.apache.commons.fileupload.FileItem;
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

import static com.conveyal.analysis.models.OpportunityDataset.ZOOM;
import static com.conveyal.analysis.util.JsonUtil.toJson;

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
    private final Config config;

    public interface Config {
        // TODO this could be eliminated by hard-wiring file types to bucket subdirectories in the FileStorage.
        String gridBucket ();
    }

    public AggregationAreaController (FileStorage fileStorage, Config config) {
        this.fileStorage = fileStorage;
        this.config = config;
    }



    private FileStorageKey getStoragePath (AggregationArea area) {
        return new FileStorageKey(config.gridBucket(), area.getS3Key());
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
        Map<String, List<FileItem>> query = HttpUtils.getRequestFiles(req.raw());

        // 1. Extract relevant files: .shp, .prj, .dbf, and .shx. ======================================================
        Map<String, FileItem> filesByName = query.get("files").stream()
                .collect(Collectors.toMap(FileItem::getName, f -> f));

        String fileName = filesByName.keySet().stream().filter(f -> f.endsWith(".shp")).findAny().orElse(null);
        if (fileName == null) {
            throw AnalysisServerException.fileUpload("Shapefile upload must contain .shp, .prj, and .dbf");
        }
        String baseName = fileName.substring(0, fileName.length() - 4);

        if (!filesByName.containsKey(baseName + ".shp") ||
                !filesByName.containsKey(baseName + ".prj") ||
                !filesByName.containsKey(baseName + ".dbf")) {
            throw AnalysisServerException.fileUpload("Shapefile upload must contain .shp, .prj, and .dbf");
        }

        String regionId = req.params("regionId");

        File tempDir = Files.createTempDir();

        File shpFile = new File(tempDir, "grid.shp");
        filesByName.get(baseName + ".shp").write(shpFile);

        File prjFile = new File(tempDir, "grid.prj");
        filesByName.get(baseName + ".prj").write(prjFile);

        File dbfFile = new File(tempDir, "grid.dbf");
        filesByName.get(baseName + ".dbf").write(dbfFile);

        // shx is optional, not needed for dense shapefiles
        if (filesByName.containsKey(baseName + ".shx")) {
            File shxFile = new File(tempDir, "grid.shx");
            filesByName.get(baseName + ".shx").write(shxFile);
        }

        // 2. Read features ============================================================================================
        ShapefileReader reader = null;
        List<SimpleFeature> features;
        try {
            reader = new ShapefileReader(shpFile);
            features = reader.wgs84Stream().collect(Collectors.toList());
        } finally {
            if (reader != null) reader.close();
        }


        Map<String, Geometry> areas = new HashMap<>();

        boolean unionRequested = Boolean.parseBoolean(query.get("union").get(0).getString());

        if (!unionRequested && features.size() > MAX_FEATURES) {
            throw AnalysisServerException.fileUpload(MessageFormat.format("The uploaded shapefile has {0} features, " +
                    "which exceeds the limit of {1}", features.size(), MAX_FEATURES));
        }

        if (unionRequested) {
            // Union (single combined aggregation area) requested
            List<Geometry> geometries = features.stream().map(f -> (Geometry) f.getDefaultGeometry()).collect(Collectors.toList());
            UnaryUnionOp union = new UnaryUnionOp(geometries);
            // Name the area using the name in the request directly
            String maskName = query.get("name").get(0).getString("UTF-8");
            areas.put(maskName, union.union());
        } else {
            // Don't union. Name each area by looking up its value for the name property in the request.
            String nameProperty = query.get("nameProperty").get(0).getString("UTF-8");
            features.forEach(f -> areas.put(readProperty(f, nameProperty), (Geometry) f.getDefaultGeometry()));
        }
        // 3. Convert to raster grids, then store them. ================================================================
        areas.forEach((String name, Geometry geometry) -> {
            Envelope env = geometry.getEnvelopeInternal();
            Grid maskGrid = new Grid(ZOOM, env);

            // Store the percentage each cell overlaps the mask, scaled as 0 to 100,000
            List<Grid.PixelWeight> weights = maskGrid.getPixelWeights(geometry, true);
            weights.forEach(pixel -> maskGrid.grid[pixel.x][pixel.y] = pixel.weight * 100_000);

            AggregationArea aggregationArea = new AggregationArea();
            aggregationArea.name = name;
            aggregationArea.regionId = regionId;

            // Set `createdBy` and `accessGroup`
            aggregationArea.accessGroup = req.attribute("accessGroup");
            aggregationArea.createdBy = req.attribute("email");

            try {
                File gridFile = FileUtils.createScratchFile("grid");
                OutputStream os = new GZIPOutputStream(FileUtils.getOutputStream(gridFile));
                maskGrid.write(os);
                os.close();

                // Create the aggregation area before generating the S3 key so that the `_id` is generated
                Persistence.aggregationAreas.create(aggregationArea);
                aggregationAreas.add(aggregationArea);

                fileStorage.moveIntoStorage(getStoragePath(aggregationArea), gridFile);
            } catch (IOException e) {
                throw new AnalysisServerException("Error processing/uploading aggregation area");
            }

            tempDir.delete();
        });

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
        return Persistence.aggregationAreas.findPermitted(
                QueryBuilder.start("regionId").is(req.params("regionId")).get(),
                req.attribute("accessGroup")
        );
    }

    private Object getAggregationArea (Request req, Response res) {
        final String accessGroup = req.attribute("accessGroup");
        final String maskId = req.params("maskId");

        AggregationArea aggregationArea = Persistence.aggregationAreas.findByIdIfPermitted(maskId, accessGroup);

        String url = fileStorage.getURL(getStoragePath(aggregationArea));
        JSONObject wrappedUrl = new JSONObject();
        wrappedUrl.put("url", url);

        return wrappedUrl;
    }

    @Override
    public void registerEndpoints (spark.Service sparkService) {
        sparkService.path("/api/region/", () -> {
            sparkService.get("/:regionId/aggregationArea", this::getAggregationAreas, toJson);
            sparkService.get("/:regionId/aggregationArea/:maskId", this::getAggregationArea, toJson);
            sparkService.post("/:regionId/aggregationArea", this::createAggregationAreas, toJson);
        });
    }

}
