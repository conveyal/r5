package com.conveyal.r5.analyst;

import com.conveyal.r5.analyst.cluster.AnalystClusterRequest;
import com.conveyal.r5.analyst.cluster.AnalystWorker;
import com.conveyal.r5.analyst.cluster.GenericClusterRequest;
import com.conveyal.r5.analyst.cluster.TaskStatistics;
import com.conveyal.r5.analyst.scenario.Scenario;
import com.conveyal.r5.api.util.LegMode;
import com.conveyal.r5.api.util.TransitModes;
import com.conveyal.r5.common.JsonUtilities;
import com.conveyal.r5.profile.ProfileRequest;
import com.conveyal.r5.profile.RepeatedRaptorProfileRouter;
import com.conveyal.r5.profile.StreetMode;
import com.conveyal.r5.streets.LinkedPointSet;
import com.conveyal.r5.transit.TransportNetwork;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.time.LocalDate;
import java.util.EnumSet;

/**
 * This class can be used to find errors in scenarios. IT IS USED FOR DEBUGGING ONLY.
 * It creates a minimal request, loads up the specified network and scenario from files, applies the scenario,
 * and runs a search.
 * It is useful when the error is happening on a network and scenario that are already present on the hosted
 * analysis software. The network and scenario can be downloaded from S3 and tested together.
 */
public class ScenarioDebugger {

    public static void main (String[] args) {
        TaskStatistics taskStats = new TaskStatistics();
        try {
            JsonUtilities.objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
            InputStream scenarioInput = new FileInputStream("/Users/abyrd/r5nl/scenario.json");
            Scenario scenario = JsonUtilities.objectMapper.readValue(scenarioInput, Scenario.class);
            scenarioInput.close();
            InputStream networkInput = new FileInputStream("/Users/abyrd/r5nl/network.dat");
            TransportNetwork network = TransportNetwork.read(networkInput);
            networkInput.close();
            AnalystClusterRequest clusterRequest = new AnalystClusterRequest();
            clusterRequest.profileRequest = new ProfileRequest();
            clusterRequest.profileRequest.scenario = scenario;
            PointSet targets = new FreeFormPointSet(0);
            LinkedPointSet linkedTargets = targets.link(network.streetLayer, StreetMode.WALK);
            clusterRequest.profileRequest.accessModes = EnumSet.of(LegMode.WALK);
            clusterRequest.profileRequest.transitModes = EnumSet.of(TransitModes.BUS);
            clusterRequest.profileRequest.fromTime = 1000;
            clusterRequest.profileRequest.toTime = 2000;
            clusterRequest.profileRequest.date = LocalDate.now();
            network = network.applyScenario(clusterRequest.profileRequest.scenario);
            RepeatedRaptorProfileRouter rrpr = new RepeatedRaptorProfileRouter(network, clusterRequest, linkedTargets, taskStats);
            rrpr.route();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
