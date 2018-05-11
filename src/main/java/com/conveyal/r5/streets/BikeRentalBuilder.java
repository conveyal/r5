package com.conveyal.r5.streets;

import com.conveyal.r5.api.util.BikeRentalStation;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.*;
import java.util.Enumeration;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loads GBFS from a file.
 */
public class BikeRentalBuilder {

    private static final Logger LOG = LoggerFactory.getLogger(BikeRentalBuilder.class);
    ZipFile zip;
    ZipEntry stationFile;

    public BikeRentalBuilder(String fileName) throws IOException {
        this.zip = new ZipFile(fileName);
        Enumeration zipEntries = this.zip.entries();
        while (zipEntries.hasMoreElements()) {
            ZipEntry entry = (ZipEntry) zipEntries.nextElement();
            if (entry.getName().equals("station_information.json")) {
                this.stationFile = entry;
            }
        }
    }

    List<BikeRentalStation> getRentalStations() {
        List<BikeRentalStation> stations = new ArrayList<>();
        try {
            InputStream stream = this.zip.getInputStream(this.stationFile);
            ObjectMapper mapper = new ObjectMapper();
            JsonNode rootNode = mapper.readTree(stream);
            JsonNode stationsNode = rootNode.path("data").path("stations");
            for (JsonNode node : stationsNode) {
                if (node == null) {
                    continue;
                }
                stations.add(makeStation(node));
            }
        } catch (IOException ex) {
            LOG.error("Failed to read GBFS bike station information from zipfile.");
            System.exit(-1);
        }

        return stations;
    }

    public static BikeRentalStation makeStation(JsonNode node) {
        BikeRentalStation station = new BikeRentalStation();
        station.id = node.path("station_id").toString();
        station.name = node.path("name").toString();
        station.lat = (float) node.path("lat").asDouble();
        station.lon = (float) node.path("lon").asDouble();
        station.spacesAvailable = node.path("capacity").asInt();
        // Assume perfect conditions, i.e. there's a bike available at every station.
        // We don't read in the realtime data about which stations have bikes.
        station.bikesAvailable = 1;
        return station;
    }
}
