package com.conveyal.r5.analyst;

import com.conveyal.osmlib.Node;
import com.conveyal.osmlib.OSM;

import java.io.FileWriter;
import java.io.Writer;
import java.util.Random;
import java.util.UUID;

/**
 * This is a main method where we can consolidate some example code used to create test fixtures.
 * In this case, CSV files for testing creation and analysis of freeform pointsets.
 * The generated CSV is part of the manual full-stack integration tests so is stored outside the Git repo.
 */
public class MakeFreeFormTestCSV {

    public static void main (String[] args) throws Exception {
        bikepark();
        gaussian();
    }

    public static void gaussian () throws Exception {
        Writer writer = new FileWriter("pdx_gaussian.csv");
        Random random = new Random();
        final double centerLat = 45.5188691;
        final double centerLon = -122.6792603;
        final double lonRadius = 0.08;
        final double latRadius = 0.04;
        final int nPoints = 2_000;
        writer.write("lat,lon,frac,whole,uuid\n");
        for (int i = 0; i < nPoints; i++) {
            double lat = random.nextGaussian() * latRadius + centerLat;
            double lon = random.nextGaussian() * lonRadius + centerLon;
            double frac = Math.abs(random.nextGaussian() * 100 + 100);
            int whole = random.nextInt(100);
            String uuid = UUID.randomUUID().toString().substring(0, 8);
            String line = String.format("%f,%f,%f,%d,%s\n", lat, lon, frac, whole, uuid);
            writer.write(line);
        }
        writer.close();
    }

    /**
     * Extract all points with certain tags from OSM data and export as CSV, adding in some random additional fields to
     * test property selection. This can be changed to extract cuisine=coffee_shop or anything else.
     */
    private static void bikepark () throws Exception {
        Writer writer = new FileWriter("pdx_bikepark.csv");
        Random random = new Random();
        OSM osm = new OSM(null);
        osm.readFromFile("pdxnodes.pbf");
        writer.write("lat,lon,junk,capacity\n");
        for (Node node : osm.nodes.values()) {
            if (node.hasTag("amenity", "bicycle_parking")) {
                int capacity = 1;
                if (node.hasTag("capacity")) {
                    capacity = Integer.parseInt(node.getTag("capacity").split(";")[0]);
                }
                String line = String.format("%f,%f,%d,%d\n", node.getLat(), node.getLon(), random.nextInt(), capacity);
                writer.write(line);
            }
        }
        writer.close();
    }

}
