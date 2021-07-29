package com.conveyal.r5.shapefile;

import com.conveyal.osmlib.OSM;
import com.conveyal.r5.point_to_point.builder.TNBuilderConfig;
import com.conveyal.r5.streets.EdgeStore;
import com.conveyal.r5.streets.StreetLayer;
import com.conveyal.r5.util.LambdaCounter;
import com.conveyal.r5.util.ShapefileReader;
import org.locationtech.jts.algorithm.distance.DiscreteHausdorffDistance;
import org.locationtech.jts.geom.Envelope;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.MultiLineString;
import org.locationtech.jts.index.strtree.STRtree;
import org.opengis.feature.Property;
import org.opengis.feature.simple.SimpleFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileWriter;
import java.util.List;
import java.util.stream.IntStream;

import static com.conveyal.r5.labeling.LevelOfTrafficStressLabeler.intToLts;
import static com.conveyal.r5.streets.EdgeStore.EdgeFlag.BIKE_LTS_EXPLICIT;

/**
 * The class ShapefileMain converts shapefiles to OSM data, which is in turn converted to R5 street networks.
 * For various reasons we may prefer to load true OSM data, then overlay the data from the shapefile onto the OSM.
 *
 * Converting shapefiles to OSM then converting that OSM to a network requires the original shapefile to be routable.
 * Nodes in shapefiles do not have identity, only location, so they're inherently ambiguous. This can be resolved in
 * two main ways: by creating new nodes wherever shapes cross, or by assuming any nodes in the same location are the
 * same node (and not two nodes stacked vertically for example). The former does not allow for separation of tunnels
 * and bridges. The latter requires exactly placed nodes on two or more features for every intersection. For example,
 * the end of a road at a T insersection requires a node in the same location on the perpendicular road.
 * It would be possible to hybridize these approaches, for example automatically inserting nodes at T intersections but
 * requiring explicit duplicate nodes at crossing intersections to distinguish them from bridges and tunnels. The
 * details get tricky though: specifically, how close does a line or point have to be to another before it's connected?
 * It is not realistic to require exact matches because data are often drawn by hand, and even machine-generated data
 * may contain roundoff errors. Roads are typically approximated by line segments whose inferred path will be dependent
 * on the projection and geometry functions used - it's not generally possible to place a point exactly "on" another
 * linestring unless that linestring already contains the same point.
 *
 * We can't allow too much slack though. Some roads genuinely come close to creating a T intersection but don't connect
 * due to a barrier such as a fence or canal. These points have critical effects on connectivity and accessibility.
 * For all these reasons we prefer to build our network from a data source like OSM where nodes have identity, and it's
 * clearly defined which roads are connected to each other and where.
 *
 * The shapefiles we wish to process may also be less detailed than our networks from OSM. In the case of speed data
 * they are often much less detailed (containing only trunk roads). In this case we want to keep the network generated
 * from the detailed OSM source data, but match the shapefile features to road segments in that network and copy over
 * attribute values (handling unmatched roads, either by interpolation or defaults).
 *
 * This class matches a supplied shapefile to an already-built network.
 */
public class ShapefileMatcher {

    public static final Logger LOG = LoggerFactory.getLogger(ShapefileMatcher.class);

    private STRtree featureIndex;
    private StreetLayer streets;
    private int ltsAttributeIndex = -1;

    public ShapefileMatcher (StreetLayer streets) {
        this.streets = streets;
    }

    /**
     * Match each pair of edges in the street layer to a feature in the shapefile. Copy LTS attribute from that feature
     * to the pair of edges, setting the BIKE_LTS_EXPLICIT flag. This will prevent Conveyal OSM-inferred LTS from
     * overwriting the shapefile-derived LTS.
     */
    public void match (String shapefileName, String attributeName) {
        try {
            indexFeatures(shapefileName, attributeName);
        } catch (Throwable t) {
            throw new RuntimeException("Could not load and index shapefile.", t);
        }
        LOG.info("Matching edges");
        // Even single-threaded this is pretty fast for small extracts, but it's readily paralellized.
        final LambdaCounter edgePairCounter =
                new LambdaCounter(LOG, streets.edgeStore.nEdgePairs(), 25_000, "Edge pair {}/{}");
        IntStream.range(0, streets.edgeStore.nEdgePairs()).parallel().forEach(edgePair -> {
            EdgeStore.Edge edge = streets.edgeStore.getCursor(edgePair * 2);
            LineString edgeGeometry = edge.getGeometry();
            SimpleFeature bestFeature = findBestMatch(edgeGeometry);
            if (bestFeature != null) {
                // Set flags on forward and backward edges to match those on feature attribute
                // TODO reuse code from LevelOfTrafficStressLabeler.label()
                int lts = ((Number) bestFeature.getAttribute(ltsAttributeIndex)).intValue();
                if (lts < 1 || lts > 4) {
                    LOG.error("Clamping LTS value to range [1...4]. Value in attribute is {}", lts);
                }
                EdgeStore.EdgeFlag ltsFlag = intToLts((int) lts);
                edge.setFlag(BIKE_LTS_EXPLICIT);
                edge.setFlag(ltsFlag);
                edge.advance();
                edge.setFlag(BIKE_LTS_EXPLICIT);
                edge.setFlag(ltsFlag);
            }
            edgePairCounter.increment();
        });
        LOG.info("Done matching edges");
    }

    // Match metric is currently Hausdorff distance, eventually replace with something that accounts for overlap length.
    private SimpleFeature findBestMatch (LineString edgeGeometry) {
        SimpleFeature bestFeature = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        List<SimpleFeature> features = featureIndex.query(edgeGeometry.getEnvelopeInternal());
        for (SimpleFeature feature : features) {
            // Note that we're using unprojected coordinates so x distance is exaggerated realtive to y.
            DiscreteHausdorffDistance dhd = new DiscreteHausdorffDistance(extractLineString(feature), edgeGeometry);
            double distance = dhd.distance();
            // distance = overlap(extractLineString(feature), edgeGeometry);
            if (bestDistance > distance) {
                bestDistance = distance;
                bestFeature = feature;
            }
        }
        return bestFeature;
    }

    // Index is in floating-point WGS84
    public void indexFeatures (String shapefileName, String attributeName) throws Throwable {
        featureIndex = new STRtree();
        ShapefileReader reader = new ShapefileReader(new File(shapefileName));
        Envelope envelope = reader.wgs84Bounds();
        LOG.info("Indexing shapefile features");
        // TODO add wgs84List(), pre-unwrap linestrings and attributes
        reader.wgs84Stream().forEach(feature -> {
            LineString featureGeom = extractLineString(feature);
            featureIndex.insert(featureGeom.getEnvelopeInternal(), feature);
        });
        featureIndex.build(); // Index is now immutable.
        ltsAttributeIndex = reader.findAttribute(attributeName, Number.class);
    }

    // All the repetitive casting for multilinestring features containing a single linestring.
    private static final LineString extractLineString (SimpleFeature feature) {
        MultiLineString multiLineString = (MultiLineString) feature.getDefaultGeometry();
        if (multiLineString.getNumGeometries() != 1) {
            throw new RuntimeException("Feature does not contain a single linestring.");
        }
        return (LineString) multiLineString.getGeometryN(0);
    }


    /// DRAFT CODE BELOW ///

    // To try: check that ends of edge are within an epsilon: scale*dx + dy < e.
    // Split edges that match but have one end that does not line up.
    // Matching can proceed by iteration over the network edges or iteration over the shapefile features. We have tried
    // both ways and found it more appropriate to iterate over the network edges, indexing the shapefile features.
    // The shapefile edges are intended to be similar to the network edges, OSM ways split at all intersections. But we
    // definitely want to find only one (or zero) shape features per edge - we don't want logic to merge shape features
    // and their numeric attributes.

    // Rather than haussdorf distance ideally we want the total length of the two that is closer than some threshold.
    // Note also that our data set contains freeways for car routing. Those should not be matched to the LTS data.
    // Their data does not include freeways, ours does because we provide car routing.

    // Optimization: filter out features that are significantly longer than the edge. This can use a faster metric like
    // Manhattan distance. Determine whether we have any need to match edges and features of different lengths.
    // Optimization: Filter out edges whose bounding box does not lie almost entirely within that of the feature.
    private double overlap (LineString a, LineString b) {
        a.buffer(1);
        throw new UnsupportedOperationException();
    }

    private void dumpFile () {
        try (FileWriter fileWriter = new FileWriter("matches.csv")) {
            fileWriter.write("edgeId,edgeGeom,featureGeom\n");
//            fileWriter.write(String.format("%d,\"%s\",\"%s\"\n",
//                    edgePair * 2,
//                    edgeGeometry.toText(),
//                    extractLineString(bestFeature).toText()
//            ));
        } catch (Throwable t) {

        }
    }

}