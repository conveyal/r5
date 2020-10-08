package com.conveyal.r5.transit;

import com.conveyal.analysis.BackendVersion;
import com.conveyal.file.FileStorage;
import com.conveyal.file.FileStorageKey;
import com.conveyal.file.FileUtils;
import com.conveyal.gtfs.GTFSCache;
import com.conveyal.r5.analyst.cluster.BundleManifest;
import com.conveyal.r5.analyst.cluster.ScenarioCache;
import com.conveyal.r5.analyst.scenario.Scenario;
import com.conveyal.r5.common.JsonUtilities;
import com.conveyal.r5.kryo.KryoNetworkSerializer;
import com.conveyal.r5.point_to_point.builder.TNBuilderConfig;
import com.conveyal.r5.profile.StreetMode;
import com.conveyal.r5.streets.OSMCache;
import com.conveyal.r5.streets.StreetLayer;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Collection;
import java.util.HashMap;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * This holds one or more TransportNetworks keyed on unique strings.
 * Because (de)serialization is now much faster than building networks from scratch, built graphs are cached on the
 * local filesystem and on S3 for later re-use.
 * Currently this holds ONLY ONE base (non-scenario) network, and evicts that network when a new network is requested.
 * However there may be many scenario networks derived from that base network, which  are stored in the scenarios
 * field of the baseNetwork.
 */
public class TransportNetworkCache {

    private static final Logger LOG = LoggerFactory.getLogger(TransportNetworkCache.class);

    /** Cache size is currently limited to one, i.e. the worker holds on to only one network at a time. */
    private static final int DEFAULT_CACHE_SIZE = 1;

    // TODO change all other caches from Guava to Caffeine caches. This one is already a Caffeine cache.
    private final LoadingCache<String, TransportNetwork> cache;

    private final FileStorage fileStorage;
    private final GTFSCache gtfsCache;
    private final OSMCache osmCache;
    private final String bucket;

    /**
     * A table of already seen scenarios, avoiding downloading them repeatedly from S3 and allowing us to replace
     * scenarios with only their IDs, and reverse that replacement later.
     */
    private final ScenarioCache scenarioCache = new ScenarioCache();

    /** Create a transport network cache. If source bucket is null, will work offline. */
    public TransportNetworkCache(FileStorage fileStorage, GTFSCache gtfsCache, OSMCache osmCache, String bucket) {
        this.osmCache = osmCache;
        this.gtfsCache = gtfsCache;
        this.bucket = bucket;
        this.cache = createCache(DEFAULT_CACHE_SIZE);
        this.fileStorage = fileStorage;
    }

    /** Convenience method that returns transport network from cache. */
    public synchronized TransportNetwork getNetwork (String networkId) {
        try {
            return cache.get(networkId);
        } catch (Exception e) {
            LOG.error("Exception while loading a transport network into the cache: {}", e.toString());
            e.printStackTrace();
            return null;
        }
    }

    /**
     * Stopgap measure to associate full scenarios with their IDs, when scenarios are sent inside single point requests.
     */
    public void rememberScenario (Scenario scenario) {
        if (scenario == null) {
            throw new AssertionError("Expecting a scenario to be embedded in this task.");
        } else {
            scenarioCache.storeScenario(scenario);
        }
    }

    /**
     * Find or create a TransportNetwork for the scenario specified in a ProfileRequest.
     * ProfileRequests may contain an embedded complete scenario, or it may contain only the ID of a scenario that
     * must be fetched from S3.
     * By design a particular scenario is always defined relative to a single base graph (it's never applied to multiple
     * different base graphs). Therefore we can look up cached scenario networks based solely on their scenarioId
     * rather than a compound key of (networkId, scenarioId).
     *
     * The fact that scenario networks are cached means that PointSet linkages will be automatically reused when
     * TODO it seems to me that this method should just take a Scenario as its second parameter, and that resolving the scenario against caches on S3 or local disk should be pulled out into a separate function
     * the problem is that then you resolve the scenario every time, even when the ID is enough to look up the already built network.
     * So we need to pass the whole task in here, so either the ID or full scenario are visible.
     */
    public synchronized TransportNetwork getNetworkForScenario (String networkId, String scenarioId) {
        // The following call clears the scenarioNetworkCache if the current base graph changes.
        // FIXME does it? What does that mean? Are we trying to say that the cache of scenario networks is cleared?
        TransportNetwork baseNetwork = this.getNetwork(networkId);
        if (baseNetwork.scenarios == null) {
            baseNetwork.scenarios = new HashMap<>();
        }

        TransportNetwork scenarioNetwork =  baseNetwork.scenarios.get(scenarioId);
        if (scenarioNetwork == null) {
            // The network for this scenario was not found in the cache. Create that scenario network and cache it.
            LOG.info("Applying scenario to base network...");
            // Fetch the full scenario if an ID was specified.
            Scenario scenario = resolveScenario(networkId, scenarioId);
            // Apply any scenario modifications to the network before use, performing protective copies where necessary.
            // We used to prepend a filter to the scenario, removing trips that are not running during the search time window.
            // However, because we are caching transportNetworks with scenarios already applied to them, we can’t use
            // the InactiveTripsFilter. The solution may be to cache linked point sets based on scenario ID but always
            // apply scenarios every time.
            scenarioNetwork = scenario.applyToTransportNetwork(baseNetwork);
            LOG.info("Done applying scenario. Caching the resulting network.");
            baseNetwork.scenarios.put(scenario.id, scenarioNetwork);
        } else {
            LOG.info("Reusing cached TransportNetwork for scenario {}.", scenarioId);
        }
        return scenarioNetwork;
    }

    private String getScenarioFilename(String networkId, String scenarioId) {
        return String.format("%s_%s.json", networkId, scenarioId);
    }

    /** If this transport network is already built and cached, fetch it quick */
    private TransportNetwork checkCached (String networkId) {
        FileStorageKey r5Key = getR5NetworkFileStorageKey(networkId);
        if (fileStorage.exists(r5Key)) {
            File r5Network = fileStorage.getFile(r5Key);
            LOG.info("Loading cached transport network at {}", r5Network);
            try {
                return KryoNetworkSerializer.read(r5Network);
            } catch (Exception e) {
                LOG.error("Exception occurred retrieving cached transport network", e);
            }
        } else {
            LOG.error("Could not find transport network " + networkId);
        }
        return null;
    }

    private String getR5NetworkFilename(String networkId) {
        return networkId + "_" + BackendVersion.instance.version + ".dat";
    }

    private FileStorageKey getR5NetworkFileStorageKey (String networkId) {
        return new FileStorageKey(bucket, getR5NetworkFilename(networkId));
    }

    /** If we did not find a cached network, build one */
    public TransportNetwork buildNetwork (String networkId) {
        TransportNetwork network;

        // check if we have a new-format bundle with a JSON manifest
        FileStorageKey manifestFileKey = new FileStorageKey(bucket,GTFSCache.cleanId(networkId) + ".json");
        if (fileStorage.exists(manifestFileKey)) {
            LOG.info("Detected new-format bundle with manifest.");
            network = buildNetworkFromManifest(networkId);
        } else {
            LOG.warn("Detected old-format bundle stored as single ZIP file");
            network = buildNetworkFromBundleZip(networkId);
        }
        network.scenarioId = networkId;

        // Networks created in TransportNetworkCache are going to be used for analysis work. Pre-compute distance tables
        // from stops to street vertices, then pre-build a linked grid pointset for the whole region. These linkages
        // should be serialized along with the network, which avoids building them when an analysis worker starts.
        // The linkage we create here will never be used directly, but serves as a basis for scenario linkages, making
        // analysis much faster to start up.
        network.transitLayer.buildDistanceTables(null);
        network.rebuildLinkedGridPointSet(StreetMode.WALK);

        // Cache the serialized network on the local filesystem.


        try {
            File cacheLocation = FileUtils.createScratchFile();
            // Serialize TransportNetwork to local cache on this worker
            KryoNetworkSerializer.write(network, cacheLocation);
            // Store locally (and on S3)
            fileStorage.moveIntoStorage(getR5NetworkFileStorageKey(networkId), cacheLocation);
        } catch (Exception e) {
            // Don't break here as we do have a network to return, we just couldn't cache it.
            LOG.error("Error saving cached network", e);
        }
        return network;
    }

    /** Build a transport network given a network ID, using a zip of all bundle files in S3 */
    private TransportNetwork buildNetworkFromBundleZip (String networkId) {
        // The location of the inputs that will be used to build this graph
        File dataDirectory = FileUtils.createScratchDirectory();
        FileStorageKey zipKey = new FileStorageKey(bucket,networkId + ".zip");
        File zipFile = fileStorage.getFile(zipKey);

        try {
            ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFile));
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                File entryDestination = new File(dataDirectory, entry.getName());
                // Are both these mkdirs calls necessary?
                entryDestination.getParentFile().mkdirs();
                if (entry.isDirectory())
                    entryDestination.mkdirs();
                else {
                    OutputStream entryFileOut = new FileOutputStream(entryDestination);
                    zis.transferTo(entryFileOut);
                    entryFileOut.close();
                }
            }
            zis.close();
        } catch (Exception e) {
            // TODO delete cache dir which is probably corrupted.
            LOG.info("Error retrieving transportation network input files", e);
            return null;
        }

        // Now we have a local copy of these graph inputs. Make a graph out of them.
        TransportNetwork network;
        try {
            network = TransportNetwork.fromDirectory(dataDirectory);
        } catch (DuplicateFeedException e) {
            LOG.error("Duplicate feeds in transport network {}", networkId, e);
            throw new RuntimeException(e);
        }

        // Set the ID on the network and its layers to allow caching linkages and analysis results.
        network.scenarioId = networkId;

        return network;
    }

    /**
     * Build a network from a JSON manifest in S3.
     * A manifest describes the locations of files used to create a bundle.
     * It contains the unique IDs of the GTFS feeds and OSM extract.
     */
    private TransportNetwork buildNetworkFromManifest (String networkId) {
        FileStorageKey manifestFileKey = new FileStorageKey(bucket, getManifestFilename(networkId));
        File manifestFile = fileStorage.getFile(manifestFileKey);
        BundleManifest manifest;

        try {
            manifest = JsonUtilities.objectMapper.readValue(manifestFile, BundleManifest.class);
        } catch (IOException e) {
            LOG.error("Error reading manifest", e);
            return null;
        }
        // FIXME duplicate code. All internal building logic should be encapsulated in a method like TransportNetwork.build(osm, gtfs1, gtfs2...)
        // We currently have multiple copies of it, in buildNetworkFromManifest and buildNetworkFromBundleZip
        // So you've got to remember to do certain things like set the network ID of the network in multiple places in the code.

        TransportNetwork network = new TransportNetwork();
        network.scenarioId = networkId;
        network.streetLayer = new StreetLayer(new TNBuilderConfig()); // TODO builderConfig
        network.streetLayer.loadFromOsm(osmCache.get(manifest.osmId));
        network.streetLayer.parentNetwork = network;
        network.streetLayer.indexStreets();

        network.transitLayer = new TransitLayer();

        manifest.gtfsIds.stream()
                .map(gtfsCache::get)
                .forEach(network.transitLayer::loadFromGtfs);

        network.transitLayer.parentNetwork = network;
        network.streetLayer.associateStops(network.transitLayer);
        network.streetLayer.buildEdgeLists();

        network.rebuildTransientIndexes();

        TransferFinder transferFinder = new TransferFinder(network);
        transferFinder.findTransfers();
        transferFinder.findParkRideTransfer();

        return network;
    }

    private String getManifestFilename(String networkId) {
        return GTFSCache.cleanId(networkId) + ".json";
    }

    private LoadingCache createCache(int size) {
        return Caffeine.newBuilder()
                .maximumSize(size)
                .build(this::loadNetwork);
    }

    /**
     * Return the graph for the given unique identifier for graph builder inputs on S3.
     * If this is the same as the last graph built, just return the pre-built graph.
     * If not, build the graph from the inputs, fetching them from S3 to the local cache as needed.
     */
    private TransportNetwork loadNetwork(String networkId) {
        LOG.info("Finding or building a TransportNetwork for ID {} and R5 version {}", networkId, BackendVersion.instance.version);

        TransportNetwork network = checkCached(networkId);
        if (network == null) {
            LOG.info("Cached transport network for id {} and R5 version {} was not found. Building the network from scratch.",
                    networkId, BackendVersion.instance.version);
            network = buildNetwork(networkId);
        }

        // TODO determine why we were manually inserting into the cache.
        // It now results in concurrent modification deadlock because it's called inside a cacheloader.
        // cache.put(networkId, network);
        return network;
    }

    /**
     * This will eventually be used in WorkerStatus to report to the backend all loaded networks, to give it hints about
     * what kind of tasks the worker is ready to work on immediately. This is made more complicated by the fact that
     * workers are started up with no networks loaded, but with the intent for them to work on a particular job. So
     * currently the workers just report which network they were started up for, and this method is not used.
     *
     * In the future, workers should just report an empty set of loaded networks, and the back end should strategically
     * send them tasks when they come on line to assign them to networks as needed. But this will require a new
     * mechanism to fairly allocate the workers to jobs.
     */
    public Set<String> getLoadedNetworkIds() {
        return cache.asMap().keySet();
    }

    public Set<String> getAppliedScenarios() {
        return cache.asMap().values().stream()
                .filter(network -> network.scenarios != null)
                .map(network -> network.scenarios.keySet())
                .flatMap(Collection::stream)
                .collect(Collectors.toSet());
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
        FileStorageKey scenarioFileKey = new FileStorageKey(bucket, getScenarioFilename(networkId, scenarioId));
        try {
            File scenarioFile = fileStorage.getFile(scenarioFileKey);
            LOG.info("Loading scenario from disk file {}", scenarioFile);
            return JsonUtilities.lenientObjectMapper.readValue(scenarioFile, Scenario.class);
        } catch (Exception e) {
            LOG.error("Could not fetch scenario {} or read it from from disk: {}", scenarioId, e.toString());
            throw new RuntimeException("Scenario could not be loaded.", e);
        }
    }

}
