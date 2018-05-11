package com.conveyal.r5.analyst;

import static java.lang.Double.parseDouble;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.TimeUnit;

import com.conveyal.r5.analyst.cluster.AnalysisTask;
import com.conveyal.r5.transit.TransportNetwork;
import com.csvreader.CsvReader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Calculates travel times from one origin to many destinations.
 */
public class OneToMany {

    private static final Logger LOG = LoggerFactory.getLogger(OneToMany.class);

    /**
     * Make a request to the One To Many router.
     * This expects that there will be a file containing a list of destinations to use for one
     * to many routing in the csv form:
     *
     * id,latitude,longitude
     * 35204515,43.6850846276,-79.4351986801
     * 35210121,43.7180928872,-79.7073758158
     * etc.
     *
     * @param request the AnalysisTask containing the parameters for the request
     * @param transportNetwork the pre-built network of streets and transit
     * @param outputDir the directory where the list of destination lives and where output will be
     * written to.
     */
    public static HashMap<String, Object> makeRequest(AnalysisTask request, TransportNetwork transportNetwork)
            throws IOException {
        // Fetch the prelinked set of one-to-many destinations that was read into the network in the build step.
        PointSetWithIds destinations = (PointSetWithIds) request.getDestinations(transportNetwork, null).get(0);

        // Execute the routing task.
        TravelTimeComputer computer = new TravelTimeComputer(request, transportNetwork);
        HashMap<String, Object> idToTravelTime = new HashMap<>();
        long startTime = System.nanoTime();
        int[] travelTimes = computer.computeTravelTimes();
        long endTime = System.nanoTime();
        long duration = (endTime - startTime);
        LOG.info("Took {} ms to run the routing task.", TimeUnit.MILLISECONDS.toSeconds(duration));
        for (int i = 0; i < travelTimes.length; i++) {
            idToTravelTime.put(destinations.points.get(i).getId(), Integer.valueOf(travelTimes[i]));
        }
        return idToTravelTime;
    }

    public static PointSetWithIds readDestinations(File pointSetFile) throws IOException {
        CsvReader reader = new CsvReader(new BufferedInputStream(new FileInputStream(pointSetFile)),
                Charset.forName("UTF-8"));
        reader.readHeaders();
        List<PointWithId> points = new ArrayList<>();
        while (reader.readRecord()) {
            double lat = parseDouble((reader.get(1)));
            double lng = parseDouble((reader.get(2)));
            points.add(new PointWithId(reader.get(0), lat, lng));
        }
        return new PointSetWithIds(points);
    }
}
