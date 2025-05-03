package com.conveyal.r5.transit;

import com.conveyal.analysis.components.Component;
import com.conveyal.analysis.datasource.DataSourceException;
import com.conveyal.file.FileStorage;
import com.conveyal.file.FileStorageKey;
import com.conveyal.file.FileUtils;
import com.conveyal.gtfs.GTFSCache;
import com.conveyal.r5.analyst.cluster.ScenarioCache;
import com.conveyal.r5.analyst.cluster.TransportNetworkConfig;
import com.conveyal.r5.analyst.scenario.Modification;
import com.conveyal.r5.analyst.scenario.RasterCost;
import com.conveyal.r5.analyst.scenario.Scenario;
import com.conveyal.r5.analyst.scenario.ShapefileLts;
import com.conveyal.r5.common.JsonUtilities;
import com.conveyal.r5.kryo.KryoNetworkSerializer;
import com.conveyal.r5.profile.StreetMode;
import com.conveyal.r5.streets.OSMCache;
import com.conveyal.r5.streets.StreetLayer;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.google.common.collect.Sets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Set;

import static com.conveyal.file.FileCategory.BUNDLES;
import static com.conveyal.file.FileCategory.DATASOURCES;

/**
 * This holds one or more TransportNetworks keyed on unique strings.
 * Because (de)serialization is now much faster than building networks from scratch, built graphs are cached on the
 * local filesystem and on S3 for later re-use.
 * Currently this holds ONLY ONE base (non-scenario) network, and evicts that network when a new network is requested.
 * However there may be many scenario networks derived from that base network, which  are stored in the scenarios
 * field of the baseNetwork.
 */
public class TransportNetworkCache implements Component {

    private static final Logger LOG = LoggerFactory.getLogger(TransportNetworkCache.class);

    /** Cache size is currently limited to one, i.e. the worker holds on to only one network at a time. */
    private static final int MAX_CACHED_NETWORKS = 1;

    /**
     * It might seem sufficient to hold only two scenarios (for single point scenario comparison). But in certain cases
     * (e.g. the regional task queue is bigger than the size of each queued regional job) we might end up working on
     * a mix of tasks from N different scenarios. Note also that scenarios hold references to their base networks, so
     * caching multiple scenario networks can theoretically keep just as many TransportNetworks in memory.
     * But in practice, in non-local (cloud) operation a given worker instance is locked to a single network for its
     * entire lifespan.
     */
    public static final int MAX_CACHED_SCENARIO_NETWORKS = 10;

    // TODO change all other caches from Guava to Caffeine caches. This one is already a Caffeine cache.
    private final LoadingCache<String, TransportNetwork> networkCache;

    private final FileStorage fileStorage;
    private final GTFSCache gtfsCache;
    private final OSMCache osmCache;

    /**
     * A table of already seen scenarios, avoiding downloading them repeatedly from S3 and allowing us to replace
     * scenarios with only their IDs, and reverse that replacement later. Note that this caches the Scenario objects
     * themselves, not the TransportNetworks built from those Scenarios.
     */
    private final ScenarioCache scenarioCache = new ScenarioCache();

    /**
     * This record type is used for the private, encapsulated cache of TransportNetworks for different scenarios.
     * Scenario IDs are unique so we could look up these networks by scenario ID alone. However the cache values need
     * to be derived entirely from the cache keys. We need some way to look up the base network so we include its ID.
     */
    private record BaseAndScenarioId (String baseNetworkId, String scenarioId) { }

    /**
     * This stores a number of lightweight scenario networks built upon the current base network.
     * Each scenario TransportNetwork has its own LinkageCache, containing LinkedPointSets that each have their own
     * EgressCostTable. In practice this can exhaust memory, e.g. after using bicycle egress for about 50 scenarios.
     * The previous hierarchical arrangement of caches has the advantage of evicting all the scenarios with the
     * associated base network, which keeps the references in the scenarios from holding on to the base network.
     * But considering that we have never started evicting networks (other than for a "cache" of one element) this
     * eviction can be handled in other ways.
     */
    private LoadingCache<BaseAndScenarioId, TransportNetwork> scenarioNetworkCache;

    /** Create a transport network cache. If source bucket is null, will work offline. */
    public TransportNetworkCache (FileStorage fileStorage, GTFSCache gtfsCache, OSMCache osmCache) {
        this.osmCache = osmCache;
        this.gtfsCache = gtfsCache;
        this.networkCache = Caffeine.newBuilder()
                .maximumSize(MAX_CACHED_NETWORKS)
                .build(this::loadNetwork);
        this.scenarioNetworkCache = Caffeine.newBuilder()
                .maximumSize(MAX_CACHED_SCENARIO_NETWORKS)
                .build(this::loadScenario);
        this.fileStorage = fileStorage;
    }

    /**
     * Find a transport network by ID, building or loading as needed from pre-existing OSM, GTFS, MapDB, or Kryo files.
     * This should never return null. If a TransportNetwork can't be built or loaded, an exception will be thrown.
     */
    public TransportNetwork getNetwork (String networkId) throws TransportNetworkException {
        try {
            return networkCache.get(networkId);
        } catch (Exception e) {
            throw new TransportNetworkException("Could not load TransportNetwork into cache. ", e);
        }
    }

    /**
     * Stopgap measure to associate full scenarios with their IDs, when scenarios are sent inside single point requests.
     */
    public void rememberScenario (Scenario scenario) {
        if (scenario == null) {
            throw new IllegalArgumentException("Expecting a scenario to be embedded in this task.");
        } else {
            scenarioCache.storeScenario(scenario);
        }
    }

    /**
     * Find or create a TransportNetwork for the scenario specified in a ProfileRequest. ProfileRequests may contain an
     * embedded complete scenario, or it may contain only the ID of a scenario that must be fetched from S3. By design
     * a particular scenario is always defined relative to a single base graph (it's never applied to multiple different
     * base graphs). Therefore we can look up cached scenario networks based solely on their scenarioId rather than a
     * compound key of (networkId, scenarioId).
     *
     * Reusing scenario networks automatically leads to reuse of the associated PointSet linkages and egress tables.
     * TODO it seems to me that this method should just take a Scenario as its second parameter, and that resolving
     *      the scenario against caches on S3 or local disk should be pulled out into a separate function.
     * The problem is that then you resolve the scenario every time, even when the ID is enough to look up the already
     * built network. So we need to pass the whole task in here, so either the ID or full scenario are visible.
     *
     * Thread safety: getNetwork and getNetworkForScenario are threadsafe caches, so access to the same key by multiple
     * threads will occur sequentially without repeatedly or simultaneously performing the same loading actions.
     * Javadoc on the Caffeine LoadingCache indicates that it will throw exceptions when the cache loader method throws
     * them, without establishing a mapping in the cache. So exceptions occurring during scenario application are
     * expected to bubble up unimpeded.
     */
    public TransportNetwork getNetworkForScenario (String networkId, String scenarioId) {
        TransportNetwork scenarioNetwork = scenarioNetworkCache.get(new BaseAndScenarioId(networkId, scenarioId));
        return scenarioNetwork;
    }

    private TransportNetwork loadScenario (BaseAndScenarioId ids) {
        TransportNetwork baseNetwork = this.getNetwork(ids.baseNetworkId());
        LOG.debug("Scenario TransportNetwork not found. Applying scenario to base network and caching it.");
        // Fetch the full scenario if an ID was specified.
        Scenario scenario = resolveScenario(ids.baseNetworkId(), ids.scenarioId());
        // Apply any scenario modifications to the network before use, performing protective copies where necessary.
        // We used to prepend a filter to the scenario, removing trips that are not running during the search time window.
        // However, because we are caching transportNetworks with scenarios already applied to them, we can’t use
        // the InactiveTripsFilter. The solution may be to cache linked point sets based on scenario ID but always
        // apply scenarios every time.
        TransportNetwork scenarioNetwork = scenario.applyToTransportNetwork(baseNetwork);
        LOG.debug("Done applying scenario. Caching the resulting network.");
        return scenarioNetwork;
    }

    public static String getScenarioFilename (String networkId, String scenarioId) {
        return String.format("%s_%s.json", networkId, scenarioId);
    }

    private static String getR5NetworkFilename (String networkId) {
        return String.format("%s_%s.dat", networkId, KryoNetworkSerializer.NETWORK_FORMAT_VERSION);
    }

    private static FileStorageKey getR5NetworkFileStorageKey (String networkId) {
        return new FileStorageKey(BUNDLES, getR5NetworkFilename(networkId));
    }

    /** @return the network configuration (AKA manifest) for the given network ID, or null if no config file exists. */
    private TransportNetworkConfig loadNetworkConfig (String networkId) {
        FileStorageKey configFileKey = new FileStorageKey(BUNDLES, getNetworkConfigFilename(networkId));
        if (!fileStorage.exists(configFileKey)) {
            return null;
        }
        File configFile = fileStorage.getFile(configFileKey);
        try {
            // Use lenient mapper to mimic behavior in objectFromRequestBody.
            // A single network configuration file might be used across several worker versions. Unknown field names
            // may be present for other worker versions unknown to this one. So we can't strictly validate field names.
            return JsonUtilities.lenientObjectMapper.readValue(configFile, TransportNetworkConfig.class);
        } catch (IOException e) {
            throw new RuntimeException("Error reading TransportNetworkConfig. Does it contain new unrecognized fields?", e);
        }
    }

    /**
     * If we did not find a cached network, build one from the input files. Should throw an exception rather than
     * returning null if for any reason it can't finish building one.
     */
    private @Nonnull TransportNetwork buildNetwork (String networkId) {
        TransportNetwork network;
        TransportNetworkConfig networkConfig = loadNetworkConfig(networkId);
        if (networkConfig == null) {
            // The switch to use JSON manifests instead of zips occurred in 32a1aebe in July 2016.
            // buildNetworkFromBundleZip was deprecated for years then removed in 2024.
            throw new RuntimeException("No network config (aka manifest) found.");
        } else {
            network = buildNetworkFromConfig(networkConfig);
        }
        network.scenarioId = networkId;

        // Pre-compute distance tables from stops out to street vertices, then pre-build a linked grid pointset for the
        // whole region covered by the street network. These tables and linkages will be serialized along with the
        // network, which avoids building them when every analysis worker starts. The linkage we create here will never
        // be used directly, but serves as a basis for scenario linkages, making analyses much faster to start up.
        // Note, this retains stop-to-vertex distances for the WALK MODE ONLY, even when they are produced as
        // intermediate results while building linkages for other modes.
        // This is a candidate for optimization if car or bicycle scenarios are slow to apply.
        network.transitLayer.buildDistanceTables(null);
        network.rebuildLinkedGridPointSet(StreetMode.WALK, StreetMode.BICYCLE, StreetMode.CAR);

        // Cache the serialized network on the local filesystem and mirror it to any remote storage.
        try {
            File cacheLocation = FileUtils.createScratchFile();
            KryoNetworkSerializer.write(network, cacheLocation);
            fileStorage.moveIntoStorage(getR5NetworkFileStorageKey(networkId), cacheLocation);
        } catch (Exception e) {
            // Tolerate exceptions here as we do have a network to return, we just failed to cache it.
            LOG.error("Error saving cached network, returning the object anyway.", e);
        }
        return network;
    }

    /**
     * Build a network from a JSON TransportNetworkConfig in file storage.
     * This describes the locations of files used to create a bundle, as well as options applied at network build time.
     * It contains the unique IDs of the GTFS feeds and OSM extract.
     */
    private TransportNetwork buildNetworkFromConfig (TransportNetworkConfig config) {
        // FIXME All internal building logic should be encapsulated in a method like TransportNetwork.build(osm,
        //  gtfs1, gtfs2...) (see various methods in TransportNetwork).

        TransportNetwork network = new TransportNetwork();

        network.streetLayer = new StreetLayer(config);

        network.streetLayer.loadFromOsm(osmCache.get(config.osmId));

        network.streetLayer.parentNetwork = network;
        network.streetLayer.indexStreets();

        network.transitLayer = new TransitLayer();

        config.gtfsIds.stream()
                .map(gtfsCache::get)
                .forEach(network.transitLayer::loadFromGtfs);

        network.transitLayer.parentNetwork = network;
        network.streetLayer.associateStops(network.transitLayer);
        network.streetLayer.buildEdgeLists();

        network.rebuildTransientIndexes();

        TransferFinder transferFinder = new TransferFinder(network);
        transferFinder.findTransfers();
        transferFinder.findParkRideTransfer();

        // Apply modifications embedded in the TransportNetworkConfig JSON
        final Set<Class<? extends Modification>> ACCEPT_MODIFICATIONS = Set.of(
                RasterCost.class, ShapefileLts.class
        );
        if (config.modifications != null) {
            // Scenario scenario = new Scenario();
            // scenario.modifications = config.modifications;
            // scenario.applyToTransportNetwork(network);
            // This is applying the modifications _without creating a scenario copy_.
            // This will destructively edit the network and will only work for certain modifications.
            for (Modification modification : config.modifications) {
                if (!ACCEPT_MODIFICATIONS.contains(modification.getClass())) {
                    throw new UnsupportedOperationException(
                        "Modification type has not been evaluated for application at network build time:" +
                        modification.getClass().toString()
                    );
                }
                modification.resolve(network);
                modification.apply(network);
            }
        }
        return network;
    }

    /**
     * Return a File for the .shp file with the given dataSourceId, ensuring all associated sidecar files are local.
     * Shapefiles are usually accessed using only the .shp file's name. The reading code will look for sidecar files of
     * the same name in the same filesystem directory. If only the .shp is requested from the FileStorage it will not
     * pull down any of the associated sidecar files from cloud storage. This method will ensure that they are all on
     * the local filesystem before we try to read the .shp.
     */
    public static File prefetchShapefile (FileStorage fileStorage, String dataSourceId) {
        // TODO Clarify FileStorage semantics: which methods cause files to be pulled down;
        //      which methods tolerate non-existing file and how do they react?
        for (String extension : List.of("shp", "shx", "dbf", "prj")) {
            FileStorageKey subfileKey = new FileStorageKey(DATASOURCES, dataSourceId, extension);
            if (fileStorage.exists(subfileKey)) {
                fileStorage.getFile(subfileKey);
            } else {
                if (!extension.equals("shx")) {
                    String filename = String.join(".", dataSourceId, extension);
                    throw new DataSourceException("Required shapefile sub-file was not found: " + filename);
                }
            }
        }
        return fileStorage.getFile(new FileStorageKey(DATASOURCES, dataSourceId, "shp"));
    }

    private String getNetworkConfigFilename (String networkId) {
        return GTFSCache.cleanId(networkId) + ".json";
    }

    /**
     * CacheLoader method, which should only be called by the LoadingCache.
     * Return the graph for the given unique identifier. Load pre-built serialized networks from local or remote
     * storage. If none is available for the given id, build the network from its inputs, fetching them from remote
     * storage to local storage as needed. Note the cache size is currently hard-wired to 1, so series of calls with
     * the same ID will return the same object, but calls with different IDs will cause it to be reloaded from files.
     * This should always return a usable TransportNetwork not null, and should throw an exception whenever it can't.
     */
    private @Nonnull TransportNetwork loadNetwork(String networkId) throws TransportNetworkException {
        LOG.debug(
            "Finding or building a TransportNetwork for ID {} with file format version {}.",
            networkId, KryoNetworkSerializer.NETWORK_FORMAT_VERSION
        );
        try {
            FileStorageKey r5Key = getR5NetworkFileStorageKey(networkId);
            if (fileStorage.exists(r5Key)) {
                File networkFile = fileStorage.getFile(r5Key);
                LOG.debug("Loading cached transport network at {}", networkFile);
                return KryoNetworkSerializer.read(networkFile);
            } else {
                LOG.debug(
                    "Cached transport network for ID {} with file format version {} was not found. Building from scratch.",
                    networkId, KryoNetworkSerializer.NETWORK_FORMAT_VERSION
                );
                return buildNetwork(networkId);
            }
        } catch (Exception e) {
            throw new TransportNetworkException("Exception occurred retrieving or building network.", e);
        }
    }

    /**
     * Given a network and scenario ID, retrieve that scenario from the local disk cache (falling back on S3).
     */
    private Scenario resolveScenario (String networkId, String scenarioId) {
        // First try to get the scenario from the local memory cache. This should be sufficient for single point tasks.
        Scenario scenario = scenarioCache.getScenario(scenarioId);
        if (scenario != null) {
            return scenario;
        }
        // If a scenario ID is supplied, it overrides any supplied full scenario.
        // There is no intermediate cache here for the scenario objects - we read them from disk files.
        // This is not a problem, they're only read once before cacheing the resulting scenario-network.
        FileStorageKey scenarioFileKey = new FileStorageKey(BUNDLES, getScenarioFilename(networkId, scenarioId));
        try {
            File scenarioFile = fileStorage.getFile(scenarioFileKey);
            LOG.debug("Loading scenario from disk file {}", scenarioFile);
            return JsonUtilities.lenientObjectMapper.readValue(scenarioFile, Scenario.class);
        } catch (Exception e) {
            LOG.error("Could not fetch scenario {} or read it from from disk: {}", scenarioId, e.toString());
            throw new RuntimeException("Scenario could not be loaded.", e);
        }
    }

}
