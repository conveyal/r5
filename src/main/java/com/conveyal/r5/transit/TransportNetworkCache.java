package com.conveyal.r5.transit;

import com.amazonaws.AmazonServiceException;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.GetObjectRequest;
import com.amazonaws.services.s3.model.S3Object;
import com.conveyal.gtfs.BaseGTFSCache;
import com.conveyal.gtfs.GTFSCache;
import com.conveyal.r5.analyst.cluster.AnalysisTask;
import com.conveyal.r5.analyst.cluster.BundleManifest;
import com.conveyal.r5.analyst.cluster.ScenarioCache;
import com.conveyal.r5.analyst.scenario.Scenario;
import com.conveyal.r5.common.JsonUtilities;
import com.conveyal.r5.common.R5Version;
import com.conveyal.r5.kryo.KryoNetworkSerializer;
import com.conveyal.r5.point_to_point.builder.TNBuilderConfig;
import com.conveyal.r5.profile.ProfileRequest;
import com.conveyal.r5.profile.StreetMode;
import com.conveyal.r5.streets.OSMCache;
import com.conveyal.r5.streets.StreetLayer;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.google.common.io.ByteStreams;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
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
 */
public class TransportNetworkCache {

    private static final Logger LOG = LoggerFactory.getLogger(TransportNetworkCache.class);

    private final AmazonS3 s3;

    private final File cacheDir;

    private final String bucket;

    private static final int DEFAULT_CACHE_SIZE = 1;

    private final LoadingCache<String, TransportNetwork> cache; // TODO change all other caches from Guava to Caffeine caches
    private final BaseGTFSCache gtfsCache;
    private final OSMCache osmCache;

    /**
     * A table of already seen scenarios, avoiding downloading them repeatedly from S3 and allowing us to replace
     * scenarios with only their IDs, and reverse that replacement later.
     */
    private final ScenarioCache scenarioCache = new ScenarioCache();

    /** Create a transport network cache. If source bucket is null, will work offline. */
    public TransportNetworkCache(String region, String bucket, File cacheDir) {
        this.cacheDir = cacheDir;
        this.bucket = bucket;
        this.cache = createCache(DEFAULT_CACHE_SIZE);
        this.gtfsCache = new GTFSCache(region, bucket, null, cacheDir);
        this.osmCache = new OSMCache(bucket, cacheDir);
        this.s3 = (bucket == null) ? null : AmazonS3ClientBuilder.defaultClient();
    }

    public TransportNetworkCache(BaseGTFSCache gtfsCache, OSMCache osmCache) {
        this.gtfsCache = gtfsCache;
        this.osmCache = osmCache;
        this.cache = createCache(DEFAULT_CACHE_SIZE);
        this.cacheDir = gtfsCache.cacheDir;
        this.bucket = gtfsCache.bucket;
        // This constructor is only called when working offline, so don't create an S3 client to avoid region settings.
        s3 = null;
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
        try {
            File cacheLocation = new File(cacheDir, getR5NetworkFilename(networkId));
            if (cacheLocation.exists())
                LOG.info("Found locally-cached TransportNetwork at {}", cacheLocation);
            else {
                LOG.info("No locally cached transport network at {}.", cacheLocation);

                if (bucket != null) {
                    LOG.info("Checking for cached transport network on S3.");
                    S3Object tn;
                    try {
                        tn = s3.getObject(bucket, getR5NetworkFilename(networkId));
                    } catch (AmazonServiceException ex) {
                        LOG.info("No cached transport network was found in S3. It will be built from scratch.");
                        return null;
                    }
                    cacheDir.mkdirs();
                    // Copy the network from S3 to our local disk for later use.
                    LOG.info("Copying pre-built transport network from S3 to local file {}", cacheLocation);
                    FileOutputStream fos = new FileOutputStream(cacheLocation);
                    InputStream is = tn.getObjectContent();
                    try {
                        ByteStreams.copy(is, fos);
                    } finally {
                        is.close();
                        fos.close();
                    }
                } else {
                    LOG.info("Transport network was not found");
                    return null;
                }
            }
            LOG.info("Loading cached transport network at {}", cacheLocation);
            return KryoNetworkSerializer.read(cacheLocation);
        } catch (Exception e) {
            LOG.error("Exception occurred retrieving cached transport network", e);
            return null;
        }
    }

    private String getR5NetworkFilename(String networkId) {
        return networkId + "_" + R5Version.version + ".dat";
    }

    /** If we did not find a cached network, build one */
    public TransportNetwork buildNetwork (String networkId) {

        TransportNetwork network;

        // check if we have a new-format bundle with a JSON manifest
        String manifestFile = GTFSCache.cleanId(networkId) + ".json";
        if (new File(cacheDir, manifestFile).exists() || bucket != null && s3.doesObjectExist(bucket, manifestFile)) {
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
        File cacheLocation = new File(cacheDir, getR5NetworkFilename(networkId));

        try {
            // Serialize TransportNetwork to local cache on this worker
            KryoNetworkSerializer.write(network, cacheLocation);
            // Upload the serialized TransportNetwork to S3
            if (bucket != null) {
                LOG.info("Uploading the serialized TransportNetwork to S3 for use by other workers.");
                s3.putObject(bucket, getR5NetworkFilename(networkId), cacheLocation);
                LOG.info("Done uploading the serialized TransportNetwork to S3.");
            } else {
                LOG.info("Network saved to cache directory, not uploading to S3 while working offline.");
            }
        } catch (Exception e) {
            // Don't break here as we do have a network to return, we just couldn't cache it.
            LOG.error("Error saving cached network", e);
            cacheLocation.delete();
        }
        return network;
    }

    /** Build a transport network given a network ID, using a zip of all bundle files in S3 */
    private TransportNetwork buildNetworkFromBundleZip (String networkId) {
        // The location of the inputs that will be used to build this graph
        File dataDirectory = new File(cacheDir, networkId);

        // If we don't have a local copy of the inputs, fetch graph data as a ZIP from S3 and unzip it.
        if( ! dataDirectory.exists() || dataDirectory.list().length == 0) {
            if (bucket != null) {
                LOG.info("Downloading graph input files from S3.");
                dataDirectory.mkdirs();
                String networkZipKey = networkId + ".zip";
                S3Object graphDataZipObject = s3.getObject(bucket, networkZipKey);
                ZipInputStream zis = new ZipInputStream(graphDataZipObject.getObjectContent());
                try {
                    ZipEntry entry;
                    while ((entry = zis.getNextEntry()) != null) {
                        File entryDestination = new File(dataDirectory, entry.getName());
                        // Are both these mkdirs calls necessary?
                        entryDestination.getParentFile().mkdirs();
                        if (entry.isDirectory())
                            entryDestination.mkdirs();
                        else {
                            OutputStream entryFileOut = new FileOutputStream(entryDestination);
                            IOUtils.copy(zis, entryFileOut);
                            entryFileOut.close();
                        }
                    }
                    zis.close();
                } catch (Exception e) {
                    // TODO delete cache dir which is probably corrupted.
                    LOG.info("Error retrieving transportation network input files", e);
                }
            } else {
                LOG.info("Input files were not found.");
                return null;
            }
        } else {
            LOG.info("Input files were found locally. Using these files from the cache.");
        }

        // Now we have a local copy of these graph inputs. Make a graph out of them.
        TransportNetwork network;
        try {
            network = TransportNetwork.fromDirectory(new File(cacheDir, networkId));
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
        String manifestFileName = getManifestFilename(networkId);
        File manifestFile = new File(cacheDir, manifestFileName);

        // TODO handle manifest not in S3
        if (!manifestFile.exists() && bucket != null) {
            LOG.info("Manifest file not found locally, downloading from S3");
            String manifestKey = getManifestFilename(networkId);
            s3.getObject(new GetObjectRequest(bucket, manifestKey), manifestFile);
        }

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
                .map(id -> gtfsCache.getFeed(id))
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
                .removalListener((networkId, network, cause) -> {
                    LOG.info("Network {} was evicted from the cache.", networkId);
                    // delete local files ONLY if using s3
                    if (bucket != null) {
                        String[] extensions = {".db", ".db.p", ".zip"};
                        // delete local cache files (including zip) when feed removed from cache
                        for (String type : extensions) {
                            File file = new File(cacheDir, networkId + type);
                            file.delete();
                        }
                    }
                })
                .build(this::loadNetwork);
    }

    /**
     * Return the graph for the given unique identifier for graph builder inputs on S3.
     * If this is the same as the last graph built, just return the pre-built graph.
     * If not, build the graph from the inputs, fetching them from S3 to the local cache as needed.
     */
    private TransportNetwork loadNetwork(String networkId) {

        LOG.info("Finding or building a TransportNetwork for ID {} and R5 version {}", networkId, R5Version.version);

        TransportNetwork network = checkCached(networkId);
        if (network == null) {
            LOG.info("Cached transport network for id {} and R5 version {} was not found. Building the network from scratch.",
                    networkId, R5Version.version);
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
        File scenarioFile = new File(cacheDir, getScenarioFilename(networkId, scenarioId));
        try {
            if (!scenarioFile.exists()) {
                LOG.info("Retrieving scenario stored separately on S3 rather than in the ProfileRequest.");
                try {
                    S3Object obj = s3.getObject(bucket, getScenarioFilename(networkId, scenarioId));
                    InputStream is = obj.getObjectContent();
                    OutputStream os = new BufferedOutputStream(new FileOutputStream(scenarioFile));
                    ByteStreams.copy(is, os);
                    is.close();
                    os.close();
                } catch (Exception e) {
                    LOG.error("Error retrieving scenario {} from S3: {}", scenarioId, e.toString());
                }
            }
            LOG.info("Loading scenario from disk file {}", scenarioFile);
            scenario = JsonUtilities.lenientObjectMapper.readValue(scenarioFile, Scenario.class);
        } catch (Exception e) {
            LOG.error("Could not fetch scenario {} or read it from from disk: {}", scenarioId, e.toString());
            throw new RuntimeException("Scenario could not be loaded.", e);
        }
        return scenario;
    }

}
