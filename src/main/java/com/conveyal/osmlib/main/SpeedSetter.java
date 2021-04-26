package com.conveyal.osmlib.main;

import com.conveyal.osmlib.OSM;
import com.conveyal.osmlib.Way;
import com.conveyal.r5.point_to_point.builder.SpeedConfig;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.HashMap;
import java.util.Map;

/**
 * This is an example utility main method for setting speeds from a csv file.
 * It's meant to be run from an IDE as part of a data preparation pipeline.
 */
public class SpeedSetter {

    public static void main (String[] args) throws Exception {
        OSM osm = new OSM(null);
        osm.readFromFile("C:/Users/Anson/Dropbox (Conveyal)/Anson/Client " +
                "Work/MassDOT/Streetlight/streetlight-ma-2019-02-04/massachusetts-200101.osm.pbf");

        SpeedConfig estimatedSpeedLimits = SpeedConfig.defaultConfig();

        Map<Long, Way> ways = new HashMap<>();

        System.out.println("Setting maxspeed:motorcar tags...");
        File speedsFile = new File("C:/Users/Anson/Dropbox (Conveyal)/Anson/Client " +
                "Work/MassDOT/Streetlight/streetlight-ma-2019-02-04/speed-extract-2019_v5.csv");

        File output = new File("C:/Users/Anson/Dropbox (Conveyal)/Anson/Client " +
                "Work/MassDOT/Streetlight/streetlight-ma-2019-02-04/speed-extract-2019_12-15_out_v5.csv");

        BufferedWriter bw = new BufferedWriter(new FileWriter(output));
        bw.write(String.join(",","osmId", "streetType", "speedMph"));
        bw.newLine();

        try(BufferedReader br = new BufferedReader(new FileReader(speedsFile))) {
            br.readLine(); // skip headers
            for(String line; (line = br.readLine()) != null; ) {
                String[] fields = line.split(",");
                long osmWayId = Long.parseLong(fields[0]);
                double speedMph = Double.parseDouble(fields[5]);
                // double walkFactor = Double.parseDouble(fields[2]);
                // double bikeFactor = Double.parseDouble(fields[2]);
                Way way = osm.ways.get(osmWayId);
                String streetType;
                if (way == null) {
                    streetType = "Missing";
                } else {
                    streetType = way.getTag("highway") == null ? "Untagged" : way.getTag("highway");
                    // TODO make this configurable (add arg "enforceSpeedLimits"?)
                    Integer speedLimit = estimatedSpeedLimits.values.getOrDefault(streetType, estimatedSpeedLimits.defaultSpeed);
                    speedMph = Math.min(speedLimit, speedMph);
                    // R5 currently prioritizes maxspeed:motorcar above all other maxspeed tags
                    way.addOrReplaceTag("maxspeed:motorcar", String.format("%1.1f mph", speedMph));
                    // way.addOrReplaceTag("walk_factor", String.format("%1.1f", walkFactor));
                    // way.addOrReplaceTag("bike_factor", String.format("%1.1f", bikeFactor));
                    ways.put(osmWayId, way);
                }
                bw.write(String.join(",", String.valueOf(osmWayId), streetType, String.valueOf(speedMph)));
                bw.newLine();
            }
        }
        osm.ways = ways;
        osm.writeToFile("C:/Users/Anson/Dropbox (Conveyal)/Anson/Client " +
                "Work/MassDOT/Streetlight/streetlight-ma-2019-02-04/output.pbf");
        bw.close();
    }

}
