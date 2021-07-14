package com.conveyal.osmlib.main;

import com.conveyal.osmlib.OSM;
import com.conveyal.osmlib.Way;
import com.conveyal.r5.point_to_point.builder.SpeedConfig;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * This is an example utility main method for setting speeds from a csv file.
 * It's meant to be run from an IDE as part of a data preparation pipeline.
 */
public class SpeedSetter {

    static final String dir = "C:/Users/Anson/Dropbox (Conveyal)/Anson/Client " +
            "Work/MassDOT/Streetlight/streetlight-ma-2019-02-04/";
    static final String osmInputFile = dir + "massachusetts-200101.osm.pbf";
    static final String speedInputFile = dir + "speed-extract-2019_v5.csv";
    static final String speedFallbackFile = dir + "speed-fallbacks-2019.csv";
    static final String osmOutputFile = dir + "output.pbf";
    static final String csvOutputFile = dir + "speeds_v5.1.csv";

    public static void main (String[] args) throws Exception {
        OSM osm = new OSM(null);
        osm.readFromFile(osmInputFile);

        // Populate map from OSM way IDs to speeds using provided CSV
        Map<Long, Double> speeds = new HashMap<>();
        File speedsFile = new File(speedInputFile);

        BufferedReader br = new BufferedReader(new FileReader(speedsFile));
        br.readLine(); // skip headers
        for (String line; (line = br.readLine()) != null; ) {
            String[] fields = line.split(",");
            long osmWayId = Long.parseLong(fields[0]);

            // For 06:00 to 09:00
            double speedMph = Double.parseDouble(fields[3]);

            // For 12:00 to 15:00
            // double speedMph = Double.parseDouble(fields[5]);

            speeds.put(osmWayId, speedMph);
        }

        // TODO read fallback speeds
        Map<Long, Double> fallbackSpeeds = new HashMap<>();

        SpeedConfig estimatedSpeedLimits = SpeedConfig.defaultConfig();

        System.out.println("Setting maxspeed:motorcar tags...");

        Map<Long, Way> ways = new HashMap<>();

        File output = new File(csvOutputFile);

        BufferedWriter bw = new BufferedWriter(new FileWriter(output));
        bw.write(String.join(",","osmId", "streetType", "speedMph"));
        bw.newLine();

        osm.ways.forEach((osmWayId, way )->  {
            String streetType;
            Double speedMph = null;

            if (way == null) {
                streetType = "Missing";
            } else {
                streetType = way.getTag("highway") == null ? "Untagged" : way.getTag("highway");
                // TODO make this configurable (add arg "enforceSpeedLimits"?)
                Integer speedLimit = estimatedSpeedLimits.values.getOrDefault(streetType, estimatedSpeedLimits.defaultSpeed);
                // TODO use fallback value by streetType instead of speed limit?
                speedMph = speeds.get(osmWayId) != null ? Math.min(speedLimit, speeds.get(osmWayId)) : speedLimit;
                // R5 currently prioritizes maxspeed:motorcar above all other maxspeed tags
                way.addOrReplaceTag("maxspeed:motorcar", String.format("%1.1f mph", speedMph));
                ways.put(osmWayId, way);
            }
            try {
                bw.write(String.join(",", String.valueOf(osmWayId), streetType, String.valueOf(speedMph)));
                bw.newLine();
            } catch (IOException e) {
                e.printStackTrace();
            }

        });
        osm.ways = ways;
        osm.writeToFile(osmOutputFile);
        bw.close();
    }

}
