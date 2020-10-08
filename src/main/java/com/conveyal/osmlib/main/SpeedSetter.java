package com.conveyal.osmlib.main;

import com.conveyal.osmlib.OSM;
import com.conveyal.osmlib.Way;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;

/**
 * This is an example utility main method for setting speeds from a csv file.
 * It's meant to be run from an IDE as part of a data preparation pipeline.
 */
public class SpeedSetter {

    public static void main (String[] args) throws Exception {
        OSM osm = new OSM(null);
        osm.readFromFile("/Users/abyrd/predicted_speeds_2015_for_conveyal.pbf");

        System.out.println("Setting maxspeed:motorcar tags...");
        File speedsFile = new File("/Users/abyrd/predicted_speeds_2015_for_conveyal.csv");
        try(BufferedReader br = new BufferedReader(new FileReader(speedsFile))) {
            br.readLine(); // skip headers
            for(String line; (line = br.readLine()) != null; ) {
                String[] fields = line.split(",");
                long osmWayId = Long.parseLong(fields[0]);
                double speedKph = Double.parseDouble(fields[1]);
                Way way = osm.ways.get(osmWayId);
                // R5 currently prioritizes maxspeed:motorcar above all other maxspeed tags
                way.addOrReplaceTag("maxspeed:motorcar", String.format("%1.1f kph", speedKph));
                osm.ways.put(osmWayId, way);
            }
        }
        osm.writeToFile("/Users/abyrd/predicted_speeds_output.pbf");
    }

}
