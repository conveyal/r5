package com.conveyal.r5;

import com.conveyal.r5.api.ProfileResponse;
import com.conveyal.r5.api.util.*;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;

/**
 * Generates dummy data for GraphQL testing
 */
public class DummyProfile {
    ProfileResponse profileResponse;
    Random random;

    public DummyProfile() throws IOException {

        random = new Random(42);
        String profileFilename = "profile.json";
        String clustersFilename = "clusters.json";
        ObjectMapper mapper = new ObjectMapper();
        this.profileResponse = mapper.readValue(getClass().getResourceAsStream("/" + profileFilename), ProfileResponse.class);
        List<StopCluster> clusters = mapper.readValue(getClass().getResourceAsStream("/" + clustersFilename), new TypeReference<List<StopCluster>>(){});

        Map<String, StopCluster> clusterIdCluster = new HashMap<>(clusters.size());
        //Sets random stopcode, zoneId and wheelchairboarding in stops
        for (StopCluster cluster: clusters) {
            for (Stop stop: cluster.stops) {
                if (random.nextFloat() > 0.6) {
                    stop.code = "MB" + random.nextInt();
                }

                if (random.nextFloat() > 0.55) {
                    stop.zoneId = "CITY" + random.nextInt();
                }

                if (random.nextFloat() > 0.58) {
                    stop.wheelchairBoarding = random.nextInt(2)+1;
                }
            }
            clusterIdCluster.put(cluster.id, cluster);
        }

        for (ProfileOption option: profileResponse.options) {
            //Sets correct clusters in transitSegments
            if (option.transit != null) {
                for (TransitSegment transitSegment: option.transit) {
                    if (clusterIdCluster.containsKey(transitSegment.getFromId())) {
                        transitSegment.from = clusterIdCluster.get(transitSegment.getFromId());
                    }
                    if (clusterIdCluster.containsKey(transitSegment.getToId())) {
                        transitSegment.to = clusterIdCluster.get(transitSegment.getToId());
                    }
                }
            }
            //Sets random elevation and alerts
            if (option.access != null) {
                option.access.stream().filter(streetSegment -> random.nextFloat() > 0.5)
                    .forEach(streetSegment -> streetSegment.elevation = generateElevation(random.nextInt(5),
                        random.nextFloat() * 500));
                option.access.stream().filter(streetSegment -> random.nextFloat() < 0.4)
                    .forEach(streetSegment -> streetSegment.alerts = generateAlerts(random.nextInt(2)+1));
            }

            //Sets random elevation and alerts
            if (option.egress != null) {
                option.egress.stream().filter(streetSegment -> random.nextFloat() > 0.5)
                    .forEach(streetSegment -> streetSegment.elevation = generateElevation(random.nextInt(5),
                        random.nextFloat() * 500));
                option.egress.stream().filter(streetSegment -> random.nextFloat() < 0.4)
                    .forEach(streetSegment -> streetSegment.alerts = generateAlerts(random.nextInt(2)+1));
            }
        }
    }

    private List<Alert> generateAlerts(int numAlerts) {
        List<Alert> alerts = new ArrayList<>(numAlerts);
        for (int i = 0; i < numAlerts; i++) {
            alerts.add(generateRandomAlert());
        }
        return alerts;
    }

    /**
     * Generates random alert
     *
     * It can be alert with description and optionally header, url and start/end date with Public transit troubles
     *
     * Or one with unpaved roads (OSM alert)
     *
     * @return
     */
    private Alert generateRandomAlert() {
        List<String> header = Arrays.asList("Bus missing", "construction", "Strike", "Unknown");
        List<String> message = Arrays.asList("Something is happening", "We no work");
        List<String> urls = Arrays.asList("http://www.marprom.si/aktualno/obvestilo/n/praznicni-vozni-red-31102015-in-01112015-184/",
            "http://www.marprom.si/aktualno/obvestilo/n/zapora-betnavske-ceste-od-13102015-do-14102015-182/");


        Alert alert = new Alert();

        if (random.nextFloat() > 0.8) {


            alert.alertDescriptionText = message.get(random.nextInt(message.size()));

            if (random.nextFloat() > 0.6) {
                alert.alertHeaderText = header.get(random.nextInt(header.size()));
            } else {
                alert.alertHeaderText = "Alert";
            }

            if ( random.nextFloat() < 0.2) {
                alert.alertUrl = urls.get(random.nextInt(urls.size()));
            }

            if (random.nextFloat() < 0.4) {
                ZonedDateTime startDate = ZonedDateTime
                    .of(2015, random.nextInt(12) + 1, random.nextInt(28) + 1, random.nextInt(24),
                        random.nextInt(60), 0, 0, ZoneId.systemDefault());
                ZonedDateTime endDate = ZonedDateTime
                    .of(2015, random.nextInt(12) + 1, random.nextInt(28) + 1, random.nextInt(24),
                        random.nextInt(60), 0, 0, ZoneId.systemDefault());

                if (startDate.isBefore(endDate)) {
                    alert.effectiveStartDate = startDate;
                    alert.effectiveEndDate = endDate;
                } else {
                    alert.effectiveEndDate = startDate;
                    alert.effectiveStartDate = endDate;
                }
            }
        } else {
            alert.alertHeaderText = "Unpaved road";
            alert.alertDescriptionText = "Here is unpaved road!";
        }

        return alert;
    }

    /**
     * Randomly generates numberOfElevations from 0 to maxHeight
     * @param numberOfelevations
     * @param maxHeight
     * @return
     */
    private List<Elevation> generateElevation(int numberOfelevations, float maxHeight) {
        double[] distances = random.doubles(numberOfelevations, 0, random.nextFloat() * 42+10).toArray();
        double[] heights = random.doubles(numberOfelevations, 0, maxHeight+23).toArray();

        Arrays.sort(distances);
        List<Elevation> elevations = new ArrayList<>();
        for (int i = 0; i < numberOfelevations; i++) {
            elevations.add(new Elevation((float) distances[i], (float) heights[i]));
        }
        return elevations;
    }

}
