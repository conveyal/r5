package com.conveyal.r5.analyst.scenario;

import com.conveyal.r5.analyst.FileCategory;
import com.conveyal.r5.analyst.cluster.AnalysisWorker;
import com.conveyal.r5.streets.EdgeStore;
import com.conveyal.r5.transit.TransportNetwork;
import com.conveyal.r5.util.ExceptionUtils;
import gnu.trove.list.TShortList;
import gnu.trove.list.array.TShortArrayList;
import gnu.trove.map.TObjectIntMap;
import gnu.trove.map.hash.TObjectIntHashMap;
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
import java.util.List;
import java.util.zip.GZIPInputStream;

/**
 * To simulate traffic congestion, apply a slow-down (or speed-up) factor to roads, according to attributes of polygon
 * features. This replaces the array of edge speeds in a scenario copy of the street layer's edge store.
 * Driving is not available as an egress mode, and the updated speed fields don't affect walking or biking, so this
 * modification should not cause distance tables to be rebuilt, though of course routing with the car mode will
 * cause a car linkage to be built.
 *
 * There are several ways to implement the assignment of a slowdown factor to each edge. These approaches will be more
 * or less efficient, depending on the parameters and polygons chosen. We must iterate over either the edges or the
 * polygons, finding which objects in the other category they intersect. Doing this efficiently requires a spatial index
 * on the second category of objects, to avoid nested iteration and possible N^2 complexity.
 *
 * We can reasonably assume that there will always be many less polygons than street edges.
 * But the total quantity of intersection and length calculations will be roughly equal no matter which is the outer
 * loop: the number of times an edge's bounding box overlaps the bounding box of a polygon. So assuming the intersection
 * math is the slow part of the process, the nesting order is not so significant.
 *
 * We already have an efficiently iterable spatial index of all the edges in the street layer, so we could iterate over
 * all polygons, and set the speed on all edges falling inside them. But some edges intersect multiple polygons. We need
 * to see all these polygons at once to decide which polygon wins. Unless we want to store a lot of auxiliary state
 * (e.g. a map from each updated edge to its current polygon object or a priority value per updated edge), we must
 * iterate over the edges instead of the polygons to have all relevant polygons in hand at once.
 *
 * Additional challenges: Which polygon should we select when there are multiple candidates? Even if all polygons are
 * non-overlapping, a road may touch more than one polygon, or may be partially inside one or more polygons and
 * partially outside all polygons. But in normal usage, it's possible for polygons to overlap partially or completely.
 * A smaller polygon may be entirely inside a larger polygon. This will be the case when a center city polygon is drawn
 * on top of a suburban polygon. We cannot use a simple rule that the polygon with the worst congestion should win,
 * because it's entirely possible that small zones of lower congestion will exist or will be modeled within larger
 * zones of lesser congestion.
 *
 * Therefore we give polygons explicit priorities, which must be positive to beat the default. In ties where the edge
 * falls within two or more polygons with the same priority, the one with the longest overlap wins.
 *
 * At first, assigning speeds to all the edges in the Netherlands from 10 polygons took 25 seconds.
 * Changing to calculate intersection lengths only to break ties, assignment takes 13 seconds.
 * With 151000 polygons, setting speeds on all edges in the Netherlands takes 1 minute and 40 seconds. It is reduced to
 * 57 seconds if we only compute edge fragment lengths in cases where it can have an effect of the scaling factor.
 *
 * Created by abyrd on 2019-03-11
 */
public class RoadCongestion extends Modification {

    private static final Logger LOG = LoggerFactory.getLogger(RoadCongestion.class);

    // Public Parameters deserialized from JSON

    /** The identifier of the polygon layer containing the speed data. */
    public String polygonLayer;

    /** The name of the attribute (floating-point) within the polygon layer that contains the speed scaling factor. */
    public String scalingAttribute = "scale";

    /** The name of the attribute (numeric) within the polygon layer that contains the polygon priority. */
    public String priorityAttribute = "priority";

    /**
     * The name of the attribute (text) within the polygon layer that contains the polygon names.
     * This is only used for logging.
     */
    public String nameAttribute = "name";

    /** The default value by which to scale when no polygon is found. */
    public double defaultScaling = 1;

    // Internal (private) fields

    private STRtree polygonSpatialIndex;

    private boolean logUpdatedEdgeCounts = true;

    // Implementations of methods for the Modification interface

    @Override
    public boolean resolve (TransportNetwork network) {
        // Rather than deferring to a polygon layer cache, we just fetch and parse the layer here, so that warnings
        // and errors can all be easily recorded and bubbled back up to the UI.
        // Polygon should only need to be fetched once when the scenario is applied, then the resulting network is cached.
        // this.features = polygonLayerCache.getPolygonFeatureCollection(this.polygonLayer);
        // Note: Newer JTS now has GeoJsonReader
        try {
            InputStream s3InputStream = AnalysisWorker.filePersistence.getData(FileCategory.POLYGON, polygonLayer);
            // To test on local files:
            //InputStream s3InputStream = new FileInputStream("/Users/abyrd/" + polygonLayer);
            // TODO handle gzip decompression in FilePersistence base class.
            if (polygonLayer.endsWith(".gz")) {
                s3InputStream = new GZIPInputStream(s3InputStream);
            }
            FeatureJSON featureJSON = new FeatureJSON();
            FeatureCollection featureCollection = featureJSON.readFeatureCollection(s3InputStream);
            LOG.info("Validating features and creating spatial index...");
            polygonSpatialIndex = new STRtree();
            FeatureType featureType = featureCollection.getSchema();
            // Check CRS. If none is present, according to GeoJSON spec it is in WGS84.
            // Unfortunately our version of Geotools cannot understand the common urn:ogc:def:crs:OGC:1.3:CRS84
            // so it's better to just remove the CRS from all input files.
            CoordinateReferenceSystem crs = featureType.getCoordinateReferenceSystem();
            if (crs != null && !DefaultGeographicCRS.WGS84.equals(crs) && !CRS.decode("CRS:84").equals(crs)) {
                errors.add("GeoJSON should specify no coordinate reference system, and contain unprojected WGS84 " +
                        "coordinates. CRS is: " + crs.toString());
            }
            // PropertyDescriptor scalingPropertyDescriptor = featureType.getDescriptor(scalingAttribute);
            // Check property type? Or should we just fail fast below?
            // scalingPropertyDescriptor.getType()...
            FeatureIterator<SimpleFeature> featureIterator = featureCollection.features();
            int featureNumber = 0;
            while (featureIterator.hasNext()) {
                featureNumber += 1;
                SimpleFeature feature = featureIterator.next();
                Geometry geometry = (Geometry) feature.getDefaultGeometry();
                // NOTE all features must have the same attributes because schema is inferred from the first feature
                Object scale = feature.getAttribute(scalingAttribute);
                Object name = feature.getAttribute(nameAttribute);
                Object priority = feature.getAttribute(priorityAttribute);
                boolean indexThisFeature = true;
                if (name == null) {
                    logUpdatedEdgeCounts = false;
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
                if (!(scale instanceof Number)) {
                    errors.add(String.format("Value '%s' of attribute '%s' of feature %d should be a number.",
                            scale, scalingAttribute, featureNumber));
                    indexThisFeature = false;
                }
                if (!(geometry instanceof Polygonal)) {
                    errors.add(String.format("Geometry of feature %d should be a Polygon or Multipolygon.", featureNumber));
                    indexThisFeature = false;
                }
                if (indexThisFeature) {
                    polygonSpatialIndex.insert(geometry.getEnvelopeInternal(), new CongestionPolygon
                            ((Polygonal) geometry,
                            (String)name,
                            ((Number)scale).doubleValue(),
                            ((Number)priority).doubleValue()));
                }
            }
            // Finalize construction of the STR tree
            polygonSpatialIndex.build();
            if (featureNumber > 100) {
                logUpdatedEdgeCounts = false;
            }
        } catch (Exception e) {
            errors.add(ExceptionUtils.asString(e));
        }
        return errors.size() > 0;
    }

    /**
     * This associates a single Polygonal Geometry with a name and other factors.
     */
    private static class CongestionPolygon {
        Geometry polygonal;
        String name;
        double scale;
        double priority;

        public CongestionPolygon (Polygonal polygonal, String name, double scale, double priority) {
            this.polygonal = (Geometry) polygonal;
            this.name = name;
            this.scale = scale;
            this.priority = priority;
        }
    }

    @Override
    public boolean apply (TransportNetwork network) {
        LOG.info("Applying road congestion...");
        // network.streetLayer is already a protective copy made by method Scenario.applyToTransportNetwork,
        // and network.streetLayer.edgeStore is already an extend-only copy.
        EdgeStore edgeStore = network.streetLayer.edgeStore;
        TShortList adjustedSpeeds = new TShortArrayList(edgeStore.speeds.size());
        EdgeStore.Edge edge = edgeStore.getCursor();
        CongestionPolygon defaultPolygon =
                new CongestionPolygon(null, "DEFAULT", defaultScaling, 0);
        TObjectIntMap<CongestionPolygon> edgeCounts = new TObjectIntHashMap<>();
        while (edge.advance()) {
            // Look up polygons in spatial index. Find the one polygon that contains most of the edge.
            // FIXME edge.getEnvelope() returns fixed point, edge.getGeometry returns floating, and there are no comments
            Geometry edgeGeometryFloating = edge.getGeometry();
            Envelope edgeEnvelope = edgeGeometryFloating.getEnvelopeInternal();
            List<CongestionPolygon> candidatePolygons = polygonSpatialIndex.query(edgeEnvelope);
            CongestionPolygon winner = defaultPolygon;
            for (CongestionPolygon candidate : candidatePolygons) {
                if (candidate.polygonal.intersects(edgeGeometryFloating)) {
                    if (winner == defaultPolygon || candidate.priority > winner.priority) {
                        winner = candidate;
                    } else if (candidate.priority == winner.priority && candidate.scale != winner.scale) {
                        // Break a tie within the same priority using length.
                        // We only bother doing these (slow) length calculations if it can affect the scaling factor.
                        double winnerLength = winner.polygonal.intersection(edgeGeometryFloating).getLength();
                        double candidateLength = candidate.polygonal.intersection(edgeGeometryFloating).getLength();;
                        if (candidateLength > winnerLength) {
                            winner = candidate;
                        }
                    }
                }
            }
            if (logUpdatedEdgeCounts) {
                edgeCounts.adjustOrPutValue(winner, 1, 1);
            }
            // TODO reconsider why we are saving cm/sec, it apparently only shaves a few percent off the file size.
            adjustedSpeeds.add((short)(edge.getSpeed() * winner.scale));
        }
        if (logUpdatedEdgeCounts) {
            edgeCounts.forEachEntry((polygon, quantity) -> {
                info.add(String.format("polygon '%s' scaled %d edges by %.2f", polygon.name, quantity, polygon.scale));
                // LOG.info("{} edges were scaled by {} via polygon {} ", quantity, polygon.scale, polygon.name);
                return true;
            });
        }
        edgeStore.speeds = adjustedSpeeds;
        return errors.size() > 0;
    }

    @Override
    public int getSortOrder () {
        // TODO Why, where should this appear in the ordering
        return 95;
    }

    @Override
    public boolean affectsStreetLayer () {
        // This modification only affects the street speeds, but changes nothing at all about public transit.
        return true;
    }

    @Override
    public boolean affectsTransitLayer () {
        // This modification only affects the street speeds, but changes nothing at all about public transit.
        return false;
    }
}
