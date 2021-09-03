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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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
    static final String osmOutputFile = dir + "2019_5.4";
    static final String csvOutputFile = dir + "speeds_v5.4";
    static final int timePeriods = 8;
    static final double fallbackMultiplier = 1.1;

    // Column numbers in supplied CSVs
    static final int speedIndexOffset = 1;
    static final int fallbackIndexOffset = 3;

    public static void main (String[] args) throws Exception {
        OSM osm = new OSM(null);
        osm.readFromFile(osmInputFile);

        // Prepare maps to store speed and fallback speed values
        List<Map<Long, Double>> speeds = new ArrayList<>();
        List<Map<String, Double>> fallbacks = new ArrayList<>();
        for (int timePeriod = 0; timePeriod < timePeriods; timePeriod++) {
            speeds.add(new HashMap<>());
            fallbacks.add(new HashMap<>());
        }

        // Populate map from OSM way IDs to speeds using provided CSV
        BufferedReader speedReader = new BufferedReader(new FileReader(speedInputFile));
        speedReader.readLine(); // skip headers
        for (String line; (line = speedReader.readLine()) != null; ) {
            String[] fields = line.split(",");
            long osmWayId = Long.parseLong(fields[0]);
            for (int timePeriod = 0; timePeriod < timePeriods; timePeriod++) {
                speeds.get(timePeriod).put(osmWayId, Double.parseDouble(fields[speedIndexOffset + timePeriod]));
            }
        }

        // Populate map from OSM highway tags to fallback speed values using provided CSV
        BufferedReader fallbackReader = new BufferedReader(new FileReader(speedFallbackFile));
        fallbackReader.readLine(); // skip headers
        for (String line; (line = fallbackReader.readLine()) != null; ) {
            String[] fields = line.split(",");
            String osmHighwayTag = fields[0];
            for (int timePeriod = 0; timePeriod < timePeriods; timePeriod++) {
                fallbacks.get(timePeriod).put(osmHighwayTag,
                        Double.parseDouble(fields[fallbackIndexOffset + timePeriod]));
            }
        }

        SpeedConfig estSpeedLimits = SpeedConfig.defaultConfig();

        System.out.println("Setting maxspeed:motorcar tags...");

        for (int timePeriod = 0; timePeriod < timePeriods; timePeriod++) {
            System.out.println("for time period " + timePeriod);

            Map<Long, Way> ways = new HashMap<>();
            File output = new File(csvOutputFile + "_" + timePeriod + ".csv");
            BufferedWriter bw = new BufferedWriter(new FileWriter(output));
            bw.write(String.join(",", "osmId", "streetType", "speedMph"));
            bw.newLine();

            int tp = timePeriod; // To satisfy Java lambda effectively final requirements
            osm.ways.forEach((osmWayId, way) -> {
                String streetType;
                Double speedMph = null;

                if (osmWayId == null || way == null) {
                    streetType = "Missing";
                } else {
                    streetType = way.getTag("highway") == null ? "Untagged" : way.getTag("highway");

                    double speed;
                    int speedLimit = estSpeedLimits.values.getOrDefault(streetType, estSpeedLimits.defaultSpeed);
                    if (speeds.get(tp).get(osmWayId) != null) {
                        // Value is present for this way in this time period. Use it, but enforce speed limit
                        speed = Math.min(speeds.get(tp).get(osmWayId), speedLimit);
                    }
                    else if (fallbacks.get(tp).get(streetType) != null) {
                        // Use the fallback speed (average speed for ways of this type in this time period). Don't
                        // enforce the speed limit for these fallback speeds.
                        speed = fallbacks.get(tp).get(streetType);
                    } else {
                        // If the speed for this way is missing, and there is no fallback speed, use the speed limit.
                        speed = speedLimit;
                    }
                    // R5 currently prioritizes maxspeed:motorcar above all other maxspeed tags
                    way.addOrReplaceTag("maxspeed:motorcar", String.format("%1.1f mph", speed));
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
            osm.writeToFile(osmOutputFile + "_" + timePeriod + ".pbf");
            bw.close();
        }
    }

}
