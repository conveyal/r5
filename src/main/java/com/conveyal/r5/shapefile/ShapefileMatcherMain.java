package com.conveyal.r5.shapefile;

import com.conveyal.osmlib.OSM;
import com.conveyal.r5.point_to_point.builder.TNBuilderConfig;
import com.conveyal.r5.streets.StreetLayer;

/**
 * This main method is a test for the ShapefileMatcher and demonstrates how it's used.
 *
 * Known problems:
 * The input shapefile has ways split at intersections with some dead-end side streets that are not in R5 networks.
 * The shapefile is of type MultiLineString but each feature contains only a single LineString. We unwrap them but we
 * should decide whether to handle this or just throw an error and request conversion.
 * The R5 network includes motorways (because we support car routing) and it tries to match these to bike LTS features.
 */
public class ShapefileMatcherMain {

    private static final String SHAPE_FILE = "/Users/abyrd/geodata/la/StressFreeStreets_Updated.shp";
    private static final String SHAPE_FILE_ATTRIBUTE = "lts_ov";
    private static final String OSM_FILE = "/Users/abyrd/geodata/la/la-reduced-matching-lts.osm.pbf";

    public static void main (String[] args) throws Throwable {
        StreetLayer streetLayer = loadStreetLayer();
        ShapefileMatcher shapefileMatcher = new ShapefileMatcher(streetLayer);
        shapefileMatcher.match(SHAPE_FILE, SHAPE_FILE_ATTRIBUTE);
    }

    private static StreetLayer loadStreetLayer () {
        OSM osm = new OSM(OSM_FILE + ".mapdb");
        osm.intersectionDetection = true;
        osm.readFromFile(OSM_FILE);
        StreetLayer streetLayer = new StreetLayer(new TNBuilderConfig());
        streetLayer.loadFromOsm(osm);
        osm.close();
        return streetLayer;
    }

}
