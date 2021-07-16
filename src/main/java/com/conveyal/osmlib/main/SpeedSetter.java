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
    static final String osmInputFile = dir + "massachusetts-200101-cropped.osm.pbf";
    static final String speedInputFile = dir + "speed-extract-2019_v5.csv";
    static final String speedFallbackFile = dir + "fallbacks.csv";
    static final String osmOutputFile = dir + "2019-12pm-3pm_5.2.pbf";
    static final String csvOutputFile = dir + "speeds-12pm-3pm_v5.2.csv";

    // Column numbers in supplied CSVs

    // For 6-9am
    // static final int speedIndex = 3;
    // static final int fallbackIndex = 5;

    // For 12-3pm
    static final int speedIndex = 5;
    static final int fallbackIndex = 7;

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
            double speedMph = Double.parseDouble(fields[speedIndex]);
            speeds.put(osmWayId, speedMph);
        }

        // Populate map from OSM highway tags to fallback speed values using provided CSV
        Map<String, Double> fallbacks = new HashMap<>();
        br = new BufferedReader(new FileReader(speedFallbackFile));
        br.readLine(); // skip headers
        for (String line; (line = br.readLine()) != null; ) {
            String[] fields = line.split(",");
            String osmHighwayTag = fields[0];
            double speedMph = Double.parseDouble(fields[fallbackIndex]);
            fallbacks.put(osmHighwayTag, speedMph);
        }

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

            if (osmWayId == null || way == null) {
                streetType = "Missing";
            } else {
                streetType = way.getTag("highway") == null ? "Untagged" : way.getTag("highway");
                // TODO make this configurable (add arg "enforceSpeedLimits"?)
                Integer speedLimit = estimatedSpeedLimits.values.getOrDefault(streetType, estimatedSpeedLimits.defaultSpeed);
                double fallbackSpeed = fallbacks.get(streetType) == null ? speedLimit : fallbacks.get(streetType);
                // TODO use fallback value by streetType instead of speed limit?
                speedMph = speeds.get(osmWayId) == null ? fallbackSpeed : Math.min(speedLimit, speeds.get(osmWayId));
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
