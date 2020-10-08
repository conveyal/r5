package com.conveyal.r5.analyst.scenario;

import com.conveyal.r5.analyst.FileCategory;
import com.conveyal.r5.analyst.cluster.AnalysisWorker;
import org.geotools.feature.FeatureCollection;
import org.geotools.feature.FeatureIterator;
import org.geotools.geojson.feature.FeatureJSON;
import org.geotools.referencing.CRS;
import org.geotools.referencing.crs.DefaultGeographicCRS;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Polygonal;
import org.locationtech.jts.index.strtree.STRtree;
import org.opengis.feature.simple.SimpleFeature;
import org.opengis.feature.type.FeatureType;
import org.opengis.referencing.crs.CoordinateReferenceSystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * This is an abstraction for the polygons used to configure the road congestion modification type and the ride hailing
 * wait time modification type. Currently it's only used by the ride hailing wait time modification but should
 * eventually also be used by road congestion.
 */
public class IndexedPolygonCollection {

    private static final Logger LOG = LoggerFactory.getLogger(IndexedPolygonCollection.class);

    /** The identifier of the polygon layer containing the speed data. */
    public final String polygonLayer;

    /** The name of the attribute (floating-point) within the polygon layer that contains the pick-up wait time (in minutes). */
    public final String dataAttribute;

    /**
     * The name of the attribute (text) within the polygon layer that contains the polygon names. Only used for logging.
     */
    public final String nameAttribute;

    /**
     * The name of the attribute (text) within the polygon layer that contains unique polygon identifiers.
     * IDs will be generated if this attribute is not supplied.
     */
    private final String idAttribute;

    /**
     * The name of the attribute (numeric) within the polygon layer that contains the polygon priority, for selecting
     * one of several overlapping polygons.
     */
    public final String priorityAttribute;

    /** The default data value to return when no polygon is found. */
    public final double defaultData;

    // Internal (private) fields.
    // These are set by the feature loading and indexing process, and have getters to ensure that they are immutable.

    private STRtree polygonSpatialIndex = new STRtree();

    private Map<String, ModificationPolygon> polygonsById = new HashMap<>();

    private int featureCount = 0;

    private final List<String> errors = new ArrayList<>();

    private boolean allPolygonsHaveNames = true;

    private boolean allPolygonsHaveIds = true;

    /** This is the polygon that will be returned when no other polygon is found. */
    private ModificationPolygon defaultPolygon;

    /**
     * Constructor - arguably this has too many positional parameters.
     * @param polygonLayer the object name on S3 containing (optionally gzipped) GeoJSON
     * @param dataAttribute the name of the polygon attribute that contains the numeric data for a given use case
     * @param idAttribute the name of the polygon attribute that gives each polygon a unique identifier
     * @param nameAttribute the name of the polygon attribute that gives each polygon a name (for logging only)
     * @param priorityAttribute the name of the attribute which determines which polygon is selected when several overlap
     * @param defaultData the default value returned when a query point doesn't lie within any polygon
     */
    public IndexedPolygonCollection (
        String polygonLayer,
        String dataAttribute,
        String idAttribute,
        String nameAttribute,
        String priorityAttribute,
        double defaultData
    ) {
        this.polygonLayer = polygonLayer;
        this.dataAttribute = dataAttribute;
        this.idAttribute = idAttribute;
        this.nameAttribute = nameAttribute;
        this.priorityAttribute = priorityAttribute;
        this.defaultData = defaultData;
        this.defaultPolygon = new ModificationPolygon(null, null, "DEFAULT", defaultData, -1);
    }

    public void loadFromS3GeoJson() throws Exception {
        InputStream s3InputStream = AnalysisWorker.filePersistence.getData(FileCategory.POLYGON, polygonLayer);
        // To test on local files:
        //InputStream s3InputStream = new FileInputStream("/Users/abyrd/" + polygonLayer);
        if (polygonLayer.endsWith(".gz")) {
            s3InputStream = new GZIPInputStream(s3InputStream);
        }
        FeatureJSON featureJSON = new FeatureJSON();
        FeatureCollection featureCollection = featureJSON.readFeatureCollection(s3InputStream);
        LOG.info("Validating features and creating spatial index...");
        FeatureType featureType = featureCollection.getSchema();
        CoordinateReferenceSystem crs = featureType.getCoordinateReferenceSystem();
        if (crs != null && !DefaultGeographicCRS.WGS84.equals(crs) && !CRS.decode("CRS:84").equals(crs)) {
            throw new RuntimeException("GeoJSON should specify no coordinate reference system, and contain unprojected " +
                    "WGS84 coordinates. CRS is: " + crs.toString());
        }
        FeatureIterator<SimpleFeature> featureIterator = featureCollection.features();
        int featureNumber = 0;
        while (featureIterator.hasNext()) {
            featureNumber += 1;
            SimpleFeature feature = featureIterator.next();
            Geometry geometry = (Geometry) feature.getDefaultGeometry();
            // NOTE all features are expected to have the same attributes; schema is inferred from the first feature.
            Object data = feature.getAttribute(dataAttribute);
            Object id = feature.getAttribute(idAttribute);
            Object name = feature.getAttribute(nameAttribute);
            Object priority = feature.getAttribute(priorityAttribute);
            boolean indexThisFeature = true;
            if (id == null) {
                allPolygonsHaveIds = false;
            } else if (!(name instanceof String)) {
                errors.add(String.format("Value '%s' of attribute '%s' of feature %d should be a string.",
                        id, idAttribute, featureNumber));
                indexThisFeature = false;
            }
            if (name == null) {
                allPolygonsHaveNames = false;
            } else if (!(name instanceof String)) {
                errors.add(String.format("Value '%s' of attribute '%s' of feature %d should be a string.",
                        name, nameAttribute, featureNumber));
                indexThisFeature = false;
            }
            if (priority == null) {
                priority = 0;
            } else if (!(priority instanceof Number)) {
                errors.add(String.format("Value '%s' of attribute '%s' of feature %d should be a number.",
                        priority, priorityAttribute, featureNumber));
                indexThisFeature = false;
            }
            if (!(data instanceof Number)) {
                errors.add(String.format("Value '%s' of attribute '%s' of feature %d should be a number.",
                        data, dataAttribute, featureNumber));
                indexThisFeature = false;
            }
            if (!(geometry instanceof Polygonal)) {
                errors.add(String.format("Geometry of feature %d should be a Polygon or Multipolygon.", featureNumber));
                indexThisFeature = false;
            }
            if (indexThisFeature) {
                ModificationPolygon polygon = new ModificationPolygon(
                    (Polygonal) geometry,
                    (String) id,
                    (String) name,
                    ((Number) data).doubleValue(),
                    ((Number) priority).doubleValue()
                );
                polygonSpatialIndex.insert(geometry.getEnvelopeInternal(), polygon);
                if (polygon.id != null) {
                    polygonsById.put(polygon.id, polygon);
                }
            }
        }
        // Finalize construction of the STR tree
        polygonSpatialIndex.build();
        featureCount = featureNumber;
    }

    public int getFeatureCount () {
        return featureCount;
    }

    public List<String> getErrors () {
        return errors;
    }

    public ModificationPolygon getById(String id) {
        return polygonsById.get(id);
    }

    /**
     * @param geometry the Geometry for which we want to find a polygon, in floating point WGS84 coordinates.
     * @return the polygon that best matches. Note that this ModificationPolygon might have a null geometry if it's
     *         the default value.
     */
    public ModificationPolygon getWinningPolygon (Geometry geometry) {
        Envelope envelope = geometry.getEnvelopeInternal();
        List<ModificationPolygon> candidatePolygons = polygonSpatialIndex.query(envelope);
        ModificationPolygon winner = this.defaultPolygon;
        for (ModificationPolygon candidate : candidatePolygons) {
            if (candidate.polygonal.intersects(geometry)) {
                if (winner == defaultPolygon || candidate.priority > winner.priority) {
                    winner = candidate;
                } else if (candidate.priority == winner.priority && candidate.data != winner.data) {
                    // Break a tie within the same priority using length.
                    // We only bother doing these (slow) length calculations if it can affect the scaling factor.
                    // NOTE this is assuming the input geometry is linear - it might be a point.
                    double winnerLength = winner.polygonal.intersection(geometry).getLength();
                    double candidateLength = candidate.polygonal.intersection(geometry).getLength();;
                    if (candidateLength > winnerLength) {
                        winner = candidate;
                    }
                }
            }
        }
        return winner;
    }

}
