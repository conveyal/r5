package com.conveyal.r5.transit;

import com.amazonaws.AmazonServiceException;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.model.GetObjectRequest;
import com.amazonaws.services.s3.model.S3Object;
import com.conveyal.gtfs.GTFSCache;
import com.conveyal.osmlib.OSMCache;
import com.conveyal.r5.analyst.cluster.BundleManifest;
import com.conveyal.r5.analyst.scenario.Scenario;
import com.conveyal.r5.common.JsonUtilities;
import com.conveyal.r5.common.R5Version;
import com.conveyal.r5.point_to_point.builder.TNBuilderConfig;
import com.conveyal.r5.profile.ProfileRequest;
import com.conveyal.r5.streets.StreetLayer;
import com.google.common.io.ByteStreams;
import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * This holds one or more TransportNetworks keyed on unique strings.
 * This is a replacement for ClusterGraphBuilder.
 * TODO this should serialize any networks it builds, attempt to reload from disk, and copy serialized networks to S3.
 * Because (de)serialization is now about 2 orders of magnitude faster than building from scratch.
 */
public class TransportNetworkCache {

    private static final Logger LOG = LoggerFactory.getLogger(TransportNetworkCache.class);

    private AmazonS3Client s3 = new AmazonS3Client();

    private final File cacheDir;

    private final String sourceBucket;

    String currentNetworkId = null;

    TransportNetwork currentNetwork = null;

    private final GTFSCache gtfsCache;
    private final OSMCache osmCache;

    @Deprecated
    public TransportNetworkCache(String sourceBucket) {
        this(sourceBucket, new File("cache", "graphs")); // reuse cached graphs from old analyst worker
    }

    /** Create a transport network cache. If source bucket is null, will work offline. */
    public TransportNetworkCache(String sourceBucket, File cacheDir) {
        this.cacheDir = cacheDir;
        this.sourceBucket = sourceBucket;
        this.gtfsCache = new GTFSCache(sourceBucket, cacheDir);
        this.osmCache = new OSMCache(sourceBucket, cacheDir);
    }

    public TransportNetworkCache(GTFSCache gtfsCache, OSMCache osmCache) {
        this.gtfsCache = gtfsCache;
        this.osmCache = osmCache;
        this.cacheDir = gtfsCache.cacheDir;
        this.sourceBucket = gtfsCache.bucket;
    }

    /** If true Analyst is running locally, do not use internet connection and remote services such as S3. */
    private boolean workOffline;

    /** This stores any number of lightweight scenario networks built upon the current base network. */
    private Map<String, TransportNetwork> scenarioNetworkCache = new HashMap<>();

    /**
     * Return the graph for the given unique identifier for graph builder inputs on S3.
     * If this is the same as the last graph built, just return the pre-built graph.
     * If not, build the graph from the inputs, fetching them from S3 to the local cache as needed.
     */
    public synchronized TransportNetwork getNetwork (String networkId) {

        LOG.info("Finding or building a TransportNetwork for ID {} and R5 version {}", networkId, R5Version.version);

        if (networkId.equals(currentNetworkId)) {
            LOG.info("Network ID has not changed. Reusing the last one that was built.");
            return currentNetwork;
        }

        TransportNetwork network = checkCached(networkId);
        if (network == null) {
            LOG.info("Cached transport network for id {} and R5 version {} was not found. Building the network from scratch.",
                    networkId, R5Version.version);
            network = buildNetwork(networkId);
        }


        // link the web mercator grid pointset that will be used for analysis
        // this will never be used directly but will be the base of the linkages used when applying scenarios
        // it took a while to figure this out, this may be the most expensive line of code in the whole project.
        network.getLinkedGridPointSet();

        currentNetwork = network;
        currentNetworkId = networkId;
        scenarioNetworkCache.clear(); // We cache only scenario graphs built upon the currently active base graph.

        return network;
    }

    /**
     * Find or create a TransportNetwork for the given
     * By design a particular scenario is always defined relative to a single base graph (it's never applied to multiple
     * different base graphs). Therefore we can look up cached scenario networks based solely on their scenarioId
     * rather than a compound key of (networkId, scenarioId).
     *
     * The fact that scenario networks are cached means that PointSet linkages will be automatically reused when
     * the scenario is found by its ID and reused.
     *
     * TODO LinkedPointSets keep a reference back to a StreetLayer which means that the network will not be completely garbage collected upon network switch
     */
    public synchronized TransportNetwork getNetworkForScenario (String networkId, ProfileRequest request) {
        String scenarioId = request.scenarioId != null ? request.scenarioId : request.scenario.id;

        // The following call clears the scenarioNetworkCache if the current base graph changes.
        TransportNetwork baseNetwork = this.getNetwork(networkId);
        TransportNetwork scenarioNetwork = scenarioNetworkCache.get(scenarioId);

        // DEBUG force scenario re-application
        // scenarioNetwork = null;

        if (scenarioNetwork == null) {
            LOG.info("Applying scenario to base network...");

            Scenario scenario;
            if (request.scenario == null && request.scenarioId != null) {
                // resolve scenario
                LOG.info("Retrieving scenario stored separately on S3 rather than in the ProfileRequest");

                String scenarioKey = String.format("%s_%s.json", networkId, scenarioId);
                File scenarioFile = new File(cacheDir, scenarioKey);

                if (!scenarioFile.exists()) {
                    try {
                        S3Object obj = s3.getObject(sourceBucket, scenarioKey);
                        InputStream is = obj.getObjectContent();
                        OutputStream os = new BufferedOutputStream(new FileOutputStream(scenarioFile));
                        ByteStreams.copy(is, os);
                        is.close();
                        os.close();
                    } catch (Exception e) {
                        LOG.info("Error retrieving scenario from S3", e);
                        return null;
                    }
                }

                try {
                    scenario = JsonUtilities.objectMapper.readValue(scenarioFile, Scenario.class);
                } catch (IOException e) {
                    LOG.error("Could not read scenario {} from disk", scenarioId, e);
                    return null;
                }
            } else if (request.scenario != null) {
                scenario = request.scenario;
            } else {
                LOG.warn("No scenario specified");
                scenario = new Scenario();
            }

            // Apply any scenario modifications to the network before use, performing protective copies where necessary.
            // Prepend a pre-filter that removes trips that are not running during the search time window.
            // FIXME Caching transportNetworks with scenarios already applied means we can’t use the InactiveTripsFilter.
            // Solution may be to cache linked point sets based on scenario ID but always apply scenarios every time.
            // scenario.modifications.add(0, new InactiveTripsFilter(baseNetwork, clusterRequest.profileRequest));
            scenarioNetwork = scenario.applyToTransportNetwork(baseNetwork);
            LOG.info("Done applying scenario. Caching the resulting network.");
            scenarioNetworkCache.put(scenario.id, scenarioNetwork);
        } else {
            LOG.info("Reusing cached TransportNetwork for scenario {}.", scenarioId);
        }
        return scenarioNetwork;
    }

    /** If this transport network is already built and cached, fetch it quick */
    private TransportNetwork checkCached (String networkId) {
        try {
            String filename = networkId + "_" + R5Version.version + ".dat";
            File cacheLocation = new File(cacheDir, networkId + "_" + R5Version.version + ".dat");
            if (cacheLocation.exists())
                LOG.info("Found locally-cached TransportNetwork at {}", cacheLocation);
            else {
                LOG.info("No locally cached transport network at {}.", cacheLocation);

                if (sourceBucket != null) {
                    LOG.info("Checking for cached transport network on S3.");
                    S3Object tn;
                    try {
                        tn = s3.getObject(sourceBucket, filename);
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
            FileInputStream fis = new FileInputStream(cacheLocation);
            try {
                return TransportNetwork.read(fis);
            } finally {
                fis.close();
            }
        } catch (Exception e) {
            LOG.error("Exception occurred retrieving cached transport network", e);
            return null;
        }
    }

    /** If we did not find a cached network, build one */
    public TransportNetwork buildNetwork (String networkId) {
        TransportNetwork network;

        // check if we have a new-format bundle with a JSON manifest
        String manifestFile = GTFSCache.cleanId(networkId) + ".json";
        if (new File(cacheDir, manifestFile).exists() || sourceBucket != null && s3.doesObjectExist(sourceBucket, manifestFile)) {
            LOG.info("Detected new-format bundle with manifest.");
            network = buildNetworkFromManifest(networkId);
        } else {
            LOG.warn("Detected old-format bundle stored as single ZIP file");
            network = buildNetworkFromBundleZip(networkId);
        }

        // cache the network
        String filename = networkId + "_" + R5Version.version + ".dat";
        File cacheLocation = new File(cacheDir, networkId + "_" + R5Version.version + ".dat");
        
        try {

            // Serialize TransportNetwork to local cache on this worker
            FileOutputStream fos = new FileOutputStream(cacheLocation);
            try {
                network.write(fos);
            } finally {
                fos.close();
            }

            // Upload the serialized TransportNetwork to S3
            if (sourceBucket != null) {
                LOG.info("Uploading the serialized TransportNetwork to S3 for use by other workers.");
                s3.putObject(sourceBucket, filename, cacheLocation);
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
            if (sourceBucket != null) {
                LOG.info("Downloading graph input files from S3.");
                dataDirectory.mkdirs();
                S3Object graphDataZipObject = s3.getObject(sourceBucket, networkId + ".zip");
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
        network.networkId = networkId;

        return network;
    }

    /** Build a network from a new style manifest JSON in S3 */
    private TransportNetwork buildNetworkFromManifest (String networkId) {
        String manifestFileName = GTFSCache.cleanId(networkId) + ".json";
        File manifestFile = new File(cacheDir, manifestFileName);

        // TODO handle manifest not in S3
        if (!manifestFile.exists() && sourceBucket != null) {
            LOG.info("Manifest file not found locally, downloading from S3");
            s3.getObject(new GetObjectRequest(sourceBucket, manifestFileName), manifestFile);
        }

        BundleManifest manifest;

        try {
            manifest = JsonUtilities.objectMapper.readValue(manifestFile, BundleManifest.class);
        } catch (IOException e) {
            LOG.error("Error reading manifest", e);
            return null;
        }

        TransportNetwork network = new TransportNetwork();
        network.streetLayer = new StreetLayer(new TNBuilderConfig()); // TODO builderConfig
        network.streetLayer.loadFromOsm(osmCache.get(manifest.osmId));
        network.streetLayer.parentNetwork = network;
        network.streetLayer.indexStreets();

        network.transitLayer = new TransitLayer();

        manifest.gtfsIds.stream()
                .map(id -> gtfsCache.get(id))
                .forEach(network.transitLayer::loadFromGtfs);

        network.transitLayer.parentNetwork = network;
        network.streetLayer.associateStops(network.transitLayer);
        network.streetLayer.buildEdgeLists();

        network.rebuildTransientIndexes();

        new TransferFinder(network).findTransfers();

        return network;
    }
}
