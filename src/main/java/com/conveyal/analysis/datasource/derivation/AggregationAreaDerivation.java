package com.conveyal.analysis.datasource.derivation;

import com.conveyal.analysis.AnalysisServerException;
import com.conveyal.analysis.UserPermissions;
import com.conveyal.analysis.datasource.DataSourceException;
import com.conveyal.analysis.datasource.SpatialAttribute;
import com.conveyal.analysis.models.AggregationArea;
import com.conveyal.analysis.models.DataGroup;
import com.conveyal.analysis.models.DataSource;
import com.conveyal.analysis.models.SpatialDataSource;
import com.conveyal.analysis.persistence.AnalysisCollection;
import com.conveyal.analysis.persistence.AnalysisDB;
import com.conveyal.file.FileCategory;
import com.conveyal.file.FileStorage;
import com.conveyal.file.FileStorageKey;
import com.conveyal.file.FileUtils;
import com.conveyal.r5.analyst.Grid;
import com.conveyal.r5.analyst.progress.ProgressListener;
import com.conveyal.r5.analyst.progress.WorkProduct;
import com.conveyal.r5.util.ShapefileReader;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.operation.union.UnaryUnionOp;
import org.opengis.feature.simple.SimpleFeature;
import spark.Request;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.zip.GZIPOutputStream;

import static com.conveyal.file.FileStorageFormat.SHP;
import static com.conveyal.r5.analyst.WebMercatorExtents.parseZoom;
import static com.conveyal.r5.analyst.progress.WorkProductType.AGGREGATION_AREA;
import static com.conveyal.r5.util.ShapefileReader.GeometryType.POLYGON;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

/**
 * Created by abyrd on 2021-09-03
 */
public class AggregationAreaDerivation implements DataDerivation<SpatialDataSource, AggregationArea> {

    /**
     * Arbitrary limit to prevent UI clutter from many aggregation areas (e.g. if someone uploads thousands of blocks).
     * Someone might reasonably request an aggregation area for each of Chicago's 50 wards, so that's a good approximate
     * limit for now.
     */
    private static final int MAX_FEATURES = 100;
    
    private final FileStorage fileStorage;
    private final UserPermissions userPermissions;
    private final String dataSourceId;
    private final String nameProperty;
    private final boolean mergePolygons;
    private final int zoom;
    private final SpatialDataSource spatialDataSource;
    private final List<SimpleFeature> finalFeatures;

    // TODO derivations could return their model objects and DataGroups so they don't need direct database and fileStorage access.
    // A DerivationProduct could be a collection of File, a Collection<M extends BaseModel> and a DataGroup.
    private final AnalysisCollection<AggregationArea> aggregationAreaCollection;
    private final AnalysisCollection<DataGroup> dataGroupCollection;

    /**
     * Extraction, validation and range checking of parameters.
     * It's kind of a red flag that we're passing Components in here. The products should probably be returned by the
     * Derivation and stored by some more general purpose wrapper so we can avoid direct file and database access here.
     * It's also not great to pass in the full request - we only need to extract and validate query parameters.
     */
    private AggregationAreaDerivation (FileStorage fileStorage, AnalysisDB database, Request req) {

        // Before kicking off asynchronous processing, range check inputs to fail fast on obvious problems.
        userPermissions = UserPermissions.from(req);
        dataSourceId = req.queryParams("dataSourceId");
        nameProperty = req.queryParams("nameProperty"); //"dist_name"; //
        zoom = parseZoom(req.queryParams("zoom"));
        mergePolygons = Boolean.parseBoolean(req.queryParams("mergePolygons"));
        checkNotNull(dataSourceId);

        AnalysisCollection<DataSource> dataSourceCollection =
                database.getAnalysisCollection("dataSources", DataSource.class);
        DataSource dataSource = dataSourceCollection.findById(dataSourceId);
        checkArgument(dataSource instanceof SpatialDataSource,
                "Only spatial data sets can be converted to aggregation areas.");
        spatialDataSource = (SpatialDataSource) dataSource;
        checkArgument(POLYGON.equals(spatialDataSource.geometryType),
                "Only polygons can be converted to aggregation areas. DataSource is: " + spatialDataSource.geometryType);
        checkArgument(SHP.equals(spatialDataSource.fileFormat),
                "Currently, only shapefiles can be converted to aggregation areas.");

        if (!mergePolygons) {
            checkNotNull(nameProperty, "You must supply a nameProperty if mergePolygons is not true.");
            SpatialAttribute sa = spatialDataSource.attributes.stream()
                    .filter(a -> a.name.equals(nameProperty))
                    .findFirst().orElseThrow(() ->
                            new IllegalArgumentException("nameProperty does not exist: " + nameProperty));
            if (sa.type == SpatialAttribute.Type.GEOM) {
                throw new IllegalArgumentException("nameProperty must be of type TEXT or NUMBER, not GEOM.");
            }
        }

        this.fileStorage = fileStorage;
        // Do not retain AnalysisDB reference, but grab the collections we need.
        // TODO cache AnalysisCollection instances and reuse? Are they threadsafe?
        aggregationAreaCollection = database.getAnalysisCollection("aggregationAreas", AggregationArea.class);
        dataGroupCollection = database.getAnalysisCollection("dataGroups", DataGroup.class);

        /*
          Implementation notes:
          Collecting all the Features to a List is a red flag for scalability, but the UnaryUnionOp used below (and the
          CascadedPolygonUnion it depends on) appear to only operate on in-memory lists. The ShapefileReader and the
          FeatureSource it contains also seem to always load all features at once. So for now we just have to tolerate
          loading the whole files into memory at once.
          If we do need to pre-process the file (here reading it and converting it to WGS84) that's not a
          constant-time operation, so it should probably be done in the async task below instead of this synchronous
          HTTP controller code.
          We may not need to union the features at all. We could just iteratively rasterize all the polygons into a
          single grid which would effectively union them. This would allow both the union and non-union case to be
          handled in a streaming fashion (in constant memory).
          This whole process needs to be audited though, it's strangely slow.
         */
        File sourceFile;
        if (SHP.equals(spatialDataSource.fileFormat)) {
            // On a newly started backend, we can't be sure any sidecar files are on the local filesystem.
            // We may want to factor this out when we use shapefile DataSources in other derivations.
            String baseName = spatialDataSource._id.toString();
            prefetchShpSidecarFiles(baseName, fileStorage);
            sourceFile = fileStorage.getFile(spatialDataSource.storageKey());
            // Reading the shapefile into a list may actually take a moment, should this be done in the async part?
            try (ShapefileReader reader = new ShapefileReader(sourceFile)) {
                finalFeatures = reader.wgs84Stream().collect(Collectors.toList());
            } catch (Exception e) {
                throw new DataSourceException("Failed to load shapefile.", e);
            }
        } else {
            // GeoJSON, GeoPackage etc.
            throw new UnsupportedOperationException("To be implemented.");
        }
        if (!mergePolygons && finalFeatures.size() > MAX_FEATURES) {
            String message = MessageFormat.format(
                    "The uploaded shapefile has {0} features, exceeding the limit of {1}",
                    finalFeatures.size(), MAX_FEATURES
            );
            throw new DataSourceException(message);
        }
    }

    public static void prefetchShpSidecarFiles (String baseName, FileStorage fileStorage) {
        prefetchDataSource(baseName, "dbf", fileStorage);
        prefetchDataSource(baseName, "shx", fileStorage);
        prefetchDataSource(baseName, "prj", fileStorage);
    }

    /** Used primarily for shapefiles where we can't be sure whether all sidecar files have been synced locally. */
    private static void prefetchDataSource (String baseName, String extension, FileStorage fileStorage) {
        FileStorageKey key = new FileStorageKey(FileCategory.DATASOURCES, baseName, extension);
        // We need to clarify the FileStorage API on which calls cause the file to be synced locally, and whether these
        // getFile tolerates getting files that do not exist. This may all become irrelevant if we use NFS.
        if (fileStorage.exists(key)) {
            fileStorage.getFile(key);
        }
    }

    @Override
    public void action (ProgressListener progressListener) throws Exception {

        ArrayList<AggregationArea> aggregationAreas = new ArrayList<>();
        String groupDescription = "z" + this.zoom + ": aggregation areas";
        DataGroup dataGroup = new DataGroup(userPermissions, spatialDataSource._id.toString(), groupDescription);

        progressListener.beginTask("Reading data source", finalFeatures.size() + 1);
        Map<String, Geometry> areaGeometries = new HashMap<>();

        if (mergePolygons) {
            // Union (single combined aggregation area) requested
            List<Geometry> geometries = finalFeatures.stream().map(f ->
                    (Geometry) f.getDefaultGeometry()).collect(Collectors.toList()
            );
            UnaryUnionOp union = new UnaryUnionOp(geometries);
            // Name the area using the name in the request directly
            areaGeometries.put(spatialDataSource.name, union.union());
        } else {
            // Don't union. Name each area by looking up its value for the name property in the request.
            finalFeatures.forEach(f -> areaGeometries.put(
                    readProperty(f, nameProperty), (Geometry) f.getDefaultGeometry())
            );
        }

        // Convert to raster grids, then store them.
        areaGeometries.forEach((String name, Geometry geometry) -> {
            if (geometry == null) throw new AnalysisServerException("Invalid geometry uploaded.");
            Envelope env = geometry.getEnvelopeInternal();
            Grid maskGrid = new Grid(zoom, env);
            progressListener.beginTask("Creating grid for " + name, 0);

            // Store the percentage each cell overlaps the mask, scaled as 0 to 100,000
            List<Grid.PixelWeight> weights = maskGrid.getPixelWeights(geometry, true);
            weights.forEach(pixel -> {
                maskGrid.grid[pixel.x][pixel.y] = pixel.weight * 100_000;
            });

            AggregationArea aggregationArea = new AggregationArea(userPermissions, name, spatialDataSource, zoom);

            try {
                File gridFile = FileUtils.createScratchFile("grid");
                OutputStream os = new GZIPOutputStream(FileUtils.getOutputStream(gridFile));
                maskGrid.write(os);
                os.close();
                aggregationArea.dataGroupId = dataGroup._id.toString();
                aggregationAreas.add(aggregationArea);
                fileStorage.moveIntoStorage(aggregationArea.getStorageKey(), gridFile);
            } catch (IOException e) {
                throw new AnalysisServerException("Error processing/uploading aggregation area");
            }
            progressListener.increment();
        });
        aggregationAreaCollection.insertMany(aggregationAreas);
        dataGroupCollection.insert(dataGroup);
        progressListener.setWorkProduct(WorkProduct.forDataGroup(AGGREGATION_AREA, dataGroup, spatialDataSource.regionId));
        progressListener.increment();

    }

    private static String readProperty (SimpleFeature feature, String propertyName) {
        try {
            return feature.getProperty(propertyName).getValue().toString();
        } catch (NullPointerException e) {
            String message = String.format("The specified property '%s' was not present on the uploaded features. " +
                    "Please verify that '%s' corresponds to a shapefile column.", propertyName, propertyName);
            throw new AnalysisServerException(message);
        }
    }

    public static AggregationAreaDerivation fromRequest (Request req, FileStorage fileStorage, AnalysisDB database) {
        return new AggregationAreaDerivation(fileStorage, database, req);
    }

    @Override
    public SpatialDataSource dataSource () {
        return spatialDataSource;
    }
    
}
