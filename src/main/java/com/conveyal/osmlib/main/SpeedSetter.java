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
        osm.readFromFile("C:/Users/Anson/Dropbox (Conveyal)/Anson/Client " +
                "Work/MassDOT/Streetlight/streetlight-ma-2019-02-04/massachusetts-200101.osm.pbf");

        System.out.println("Setting maxspeed:motorcar tags...");
        File speedsFile = new File("C:/Users/Anson/Dropbox (Conveyal)/Anson/Client " +
                "Work/MassDOT/Streetlight/streetlight-ma-2019-02-04/speed-extract-2019.csv");
        try(BufferedReader br = new BufferedReader(new FileReader(speedsFile))) {
            br.readLine(); // skip headers
            for(String line; (line = br.readLine()) != null; ) {
                String[] fields = line.split(",");
                long osmWayId = Long.parseLong(fields[0]);
                double speedMph = Double.parseDouble(fields[1]);
                if (fields.length > 10 && !fields[10].isEmpty()) {
                    speedMph = Double.parseDouble(fields[10]);
                }
                // double walkFactor = Double.parseDouble(fields[2]);
                // double bikeFactor = Double.parseDouble(fields[2]);
                Way way = osm.ways.get(osmWayId);
                if (way == null) {
                    System.out.println("Missing " + osmWayId);
                } else {
                    // R5 currently prioritizes maxspeed:motorcar above all other maxspeed tags
                    way.addOrReplaceTag("maxspeed:motorcar", String.format("%1.1f mph", speedMph));
                    // way.addOrReplaceTag("walk_factor", String.format("%1.1f", walkFactor));
                    // way.addOrReplaceTag("bike_factor", String.format("%1.1f", bikeFactor));
                    osm.ways.put(osmWayId, way);
                }
            }
        }
        osm.writeToFile("C:/Users/Anson/Dropbox (Conveyal)/Anson/Client " +
                "Work/MassDOT/Streetlight/streetlight-ma-2019-02-04/output.pbf");
    }

}
