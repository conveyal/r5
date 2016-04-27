package com.conveyal.r5.transit;

import com.amazonaws.AmazonServiceException;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.model.S3Object;
import com.conveyal.r5.analyst.scenario.InactiveTripsFilter;
import com.conveyal.r5.analyst.scenario.Scenario;
import com.google.common.io.ByteStreams;
import org.apache.commons.io.IOUtils;
import com.conveyal.r5.common.MavenVersion;
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

    private static final File CACHE_DIR = new File("cache", "graphs"); // reuse cached graphs from old analyst worker

    private final String sourceBucket;

    String currentNetworkId = null;

    TransportNetwork currentNetwork = null;

    public TransportNetworkCache(String sourceBucket) {
        this.sourceBucket = sourceBucket;
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

        LOG.info("Finding or building a TransportNetwork for ID {} and R5 commit {}", networkId, MavenVersion.commit);

        if (networkId.equals(currentNetworkId)) {
            LOG.info("Network ID has not changed. Reusing the last one that was built.");
            return currentNetwork;
        }

        TransportNetwork network = checkCached(networkId);
        if (network == null) {
            LOG.info("Cached transport network for id {} and commit {} was not found. Building the network from scratch.",
                    networkId, MavenVersion.commit);
            network = buildNetwork(networkId);
        }

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
    public synchronized TransportNetwork getNetworkForScenario (String networkId, Scenario scenario) {
        // The following call clears the scenarioNetworkCache if the current base graph changes.
        TransportNetwork baseNetwork = this.getNetwork(networkId);
        TransportNetwork scenarioNetwork = scenarioNetworkCache.get(scenario.id);
        if (scenarioNetwork == null) {
            LOG.info("Applying scenario to base network...");
            // Apply any scenario modifications to the network before use, performing protective copies where necessary.
            // Prepend a pre-filter that removes trips that are not running during the search time window.
            // FIXME Caching transportNetworks with scenarios already applied means we can’t use the InactiveTripsFilter. Solution may be to cache linked point sets based on scenario ID but always apply scenarios every time.
            // scenario.modifications.add(0, new InactiveTripsFilter(baseNetwork, clusterRequest.profileRequest));
            scenarioNetwork = scenario.applyToTransportNetwork(baseNetwork);
            LOG.info("Done applying scenario. Caching the resulting network.");
            scenarioNetworkCache.put(scenario.id, scenarioNetwork);
        } else {
            LOG.info("Reusing cached TransportNetwork for scenario {}.", scenario.id);
        }
        return scenarioNetwork;
    }

    /** If this transport network is already built and cached, fetch it quick */
    private TransportNetwork checkCached (String networkId) {
        try {
            String filename = networkId + "_" + MavenVersion.commit + ".dat";
            File cacheLocation = new File(CACHE_DIR, networkId + "_" + MavenVersion.commit + ".dat");
            if (cacheLocation.exists())
                LOG.info("Found locally-cached TransportNetwork at {}", cacheLocation);
            else {
                LOG.info("No locally cached transport network at {}.", cacheLocation);
                LOG.info("Checking for cached transport network on S3.");
                S3Object tn;
                try {
                    tn = s3.getObject(sourceBucket, filename);
                } catch (AmazonServiceException ex) {
                    LOG.info("No cached transport network was found in S3. It will be built from scratch.");
                    return null;
                }
                CACHE_DIR.mkdirs();
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
        // The location of the inputs that will be used to build this graph
        File dataDirectory = new File(CACHE_DIR, networkId);

        // If we don't have a local copy of the inputs, fetch graph data as a ZIP from S3 and unzip it.
        if( ! dataDirectory.exists() || dataDirectory.list().length == 0) {
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
            LOG.info("Input files were found locally. Using these files from the cache.");
        }

        // Now we have a local copy of these graph inputs. Make a graph out of them.
        TransportNetwork network = TransportNetwork.fromDirectory(new File(CACHE_DIR, networkId));

        // Set the ID on the network and its layers to allow caching linkages and analysis results.
        network.networkId = networkId;
        network.streetLayer.streetLayerId = networkId;

        // cache the network
        String filename = networkId + "_" + MavenVersion.commit + ".dat";
        File cacheLocation = new File(CACHE_DIR, networkId + "_" + MavenVersion.commit + ".dat");
        
        try {

            // Serialize TransportNetwork to local cache on this worker
            FileOutputStream fos = new FileOutputStream(cacheLocation);
            try {
                network.write(fos);
            } finally {
                fos.close();
            }

            // Upload the serialized TransportNetwork to S3
            LOG.info("Uploading the serialized TransportNetwork to S3 for use by other workers.");
            s3.putObject(sourceBucket, filename, cacheLocation);
            LOG.info("Done uploading the serialized TransportNetwork to S3.");

        } catch (Exception e) {
            // Don't break here as we do have a network to return, we just couldn't cache it.
            LOG.error("Error saving cached network", e);
            cacheLocation.delete();
        }

        return network;
    }
}
