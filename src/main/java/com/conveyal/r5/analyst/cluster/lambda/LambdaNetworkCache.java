package com.conveyal.r5.analyst.cluster.lambda;

import com.amazonaws.AmazonServiceException;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.model.S3Object;
import com.conveyal.r5.analyst.scenario.Scenario;
import com.conveyal.r5.common.MavenVersion;
import com.conveyal.r5.transit.TransportNetwork;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.LoadingCache;
import com.google.common.io.ByteStreams;
import com.google.common.io.Files;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Grab a cached network from S3 for use in Lambda.
 *
 * This is similar to TransportNetworkCache but makes a serious effort to save disk space (on Lambda we have only 500MB)
 * and memory (1.5GB).
 */
public class LambdaNetworkCache {
    private static final Logger LOG = LoggerFactory.getLogger(LambdaNetworkCache.class);
    private TransportNetwork currentNetwork;
    private Cache<String, TransportNetwork> scenarioCache;
    private String currentNetworkId;

    /** Maximum number of scenarios to cache */
    public static final int MAX_CACHED_SCENARIOS = 5;

    private AmazonS3 s3 = new AmazonS3Client();

    public TransportNetwork getNetworkForScenario(String bucket, String networkId, Scenario scenario) {
        if (!networkId.equals(currentNetworkId)) {
            synchronized (this) {
                if (!networkId.equals(currentNetworkId)) {
                    getNetwork(bucket, networkId);
                }
            }
        }

        // no reason to apply an empty scenario
        if (scenario.modifications == null || scenario.modifications.isEmpty()) return currentNetwork;

        try {
            return scenarioCache.get(scenario.id, () -> scenario.applyToTransportNetwork(currentNetwork));
        } catch (ExecutionException e) {
            throw new RuntimeException(e.getCause());
        }
    }

    /** load up the requested network */
    private void getNetwork(String bucket, String networkId) {
        // clear cache, allow gc to do its magic, don't hold on to old base networks by reference from scenario networks
        scenarioCache = CacheBuilder.newBuilder()
                .maximumSize(MAX_CACHED_SCENARIOS)
                .build();

        // for now, assume there is a cached network for this R5 version, and explode if not
        // TODO validate inputs.
        S3Object obj;

        try {
            obj = s3.getObject(bucket, String.format(Locale.US, "%s_%s.dat", networkId, MavenVersion.commit));
        } catch (AmazonServiceException e) {
            LOG.warn("Building network from scratch as it was not found cached on S3");
            buildNetwork(bucket, networkId);
            return;
        }

        // Read it in a streaming fashion
        try {
            InputStream is = obj.getObjectContent();
            currentNetwork = TransportNetwork.read(is);
            currentNetworkId = networkId;
            is.close();
        } catch (Exception e) {
            // TODO report errors to client
            throw new RuntimeException(e);
        }
    }

    /** build the requested network */
    private void buildNetwork (String bucket, String networkId) {
        File directory = Files.createTempDir();

        try {
            LOG.info("Building graph");
            S3Object obj = s3.getObject(bucket, String.format(Locale.US, "%s.zip", networkId));

            ZipInputStream zis = new ZipInputStream(new BufferedInputStream(obj.getObjectContent()));

            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                String[] nameComponents = entry.getName().split("/");
                String cleanedName = nameComponents[nameComponents.length - 1].replaceAll("[^a-zA-Z0-9\\.]", "_");
                OutputStream os = new BufferedOutputStream(new FileOutputStream(new File(directory, cleanedName)));
                ByteStreams.copy(zis, os);
                os.close();
                // don't close zip input stream, as more entries must be read
            }
            zis.close();

            this.currentNetwork = TransportNetwork.fromDirectory(directory);

            // this is an inefficient hack, we're computing the normal stop trees, using them to link a pointset, and
            // then nulling them out

            // this will cause linked point set to be cached so it can be used after we null the stop trees
            this.currentNetwork.getLinkedGridPointSet();
            this.currentNetwork.transitLayer.stopTrees = null;
            this.currentNetwork.transitLayer.buildGridStopTrees();

            this.currentNetworkId = networkId;
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            // clean up
            directory.delete();
        }

        // upload to s3 for use in the future
        // Serialize TransportNetwork to local cache on this worker
        File networkCache = null;
        try {
            networkCache = File.createTempFile(networkId, ".dat");
            FileOutputStream fos = new FileOutputStream(networkCache);
            try {
                this.currentNetwork.write(fos);
            } finally {
                fos.close();
            }

            // Upload the serialized TransportNetwork to S3
            LOG.info("Uploading the serialized TransportNetwork to S3 for use by other workers.");
            s3.putObject(bucket, String.format(Locale.US, "%s_%s.dat", networkId, MavenVersion.commit), networkCache);
            LOG.info("Done uploading the serialized TransportNetwork to S3.");
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            if (networkCache != null) networkCache.delete();
        }
    }
}
