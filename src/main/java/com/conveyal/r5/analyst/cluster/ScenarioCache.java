package com.conveyal.r5.analyst.cluster;

import com.conveyal.r5.analyst.scenario.Scenario;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * For single point requests, the full expanded JSON representation of the scenario is included in the request.
 * For regional analyses, only the UUID of the scenario is included, and the scenario is fetched by the workers from
 * S3. In order to allow async loading of resources needed to perform an analysis, we need a compound unique key to
 * represent all the necessary elements. The full scenario must be replaced by its ID in the key, otherwise the same
 * system cannot work for the regional analyses and anyway we don't want a huge string containing all the JSON of the
 * scenario used in a map key.
 *
 * However, the full text of the scenario will be needed once all the other data is loaded and prepared. Therefore we
 * need a lookup table from IDs to scenarios, which can be filled in as new scenarios are received.
 *
 * This could also encapsulate any logic for saving and loading those scenarios from S3.
 *
 * It's worth asking whether we should have UUIDs for these scenarios or just hash them to make IDs. Because as it is
 * someone can send two different scenarios with the same ID, or a single point request that provides a scenario
 * that is different than the one on S3 for a given scenario ID. Even better would be to let the workers request the
 * scenarios from the backend instead of from S3.
 *
 * TODO merge this with TransportNetworkCache#resolveScenario into a single multi-level mem/disk/s3 cache.
 * Note that this cache is going to just grow indefinitely in size as a worker receives many iterations of the same
 * scenario - that could be a memory leak. Again multi level caching could releive those worries.
 * It's debatable whether we should be hanging on to scenarios passed with single point requests becuase they may never
 * be used again.
 * Should we just always require a single point task to be sent to the cluster before a regional?
 * That would not ensure the scenario was present on all workers though.
 *
 * Created by abyrd on 2018-10-29
 */
public class ScenarioCache {

    private static final Logger LOG = LoggerFactory.getLogger(ScenarioCache.class);

    private Map<String, Scenario> scenariosById = new HashMap<>();

    public synchronized void storeScenario (Scenario scenario) {
        Scenario existingScenario = scenariosById.put(scenario.id, scenario);
        if (existingScenario != null) {
            LOG.debug("Scenario cache already contained a this scenario.");
        }
    }

    public synchronized Scenario getScenario (String scenarioId) {
        return scenariosById.get(scenarioId);
    }

}
