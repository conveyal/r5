package com.conveyal.data.census;

import com.conveyal.data.geobuf.GeobufEncoder;
import com.conveyal.data.geobuf.GeobufFeature;
import com.google.common.collect.Iterators;
import com.google.common.collect.PeekingIterator;
import org.locationtech.jts.geom.Envelope;
import org.mapdb.BTreeKeySerializer;
import org.mapdb.BTreeMap;
import org.mapdb.DB;
import org.mapdb.DBMaker;
import org.mapdb.Fun;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableSet;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;

/**
 * Store geographic data by ID, with index by zoom-11 tile.
 */
public class ShapeDataStore {
    public static final int ZOOM_LEVEL = 11;

    private static final Logger LOG = LoggerFactory.getLogger(ShapeDataStore.class);

    /** Number of decimal places of precision to store */
    public static final int PRECISION = 12;

    private DB db;

    /** Set of 3-tuples of (x, y, feature_id) grouping features into tiles at zoom level 11 */
    private NavigableSet<Fun.Tuple3<Integer, Integer, Long>> tiles;

    /** Map from feature_id to feature */
    private BTreeMap<Long, GeobufFeature> features;

    public ShapeDataStore() {
        db = DBMaker.newTempFileDB().deleteFilesAfterClose().asyncWriteEnable()
                .transactionDisable()
                .mmapFileEnable()
                .asyncWriteEnable()
                .asyncWriteFlushDelay(1000)
                .asyncWriteQueueSize(10000)
                .make();

        features = db.createTreeMap("features")
                // TODO check that all keys are non-negative.
                .keySerializer(BTreeKeySerializer.ZERO_OR_POSITIVE_LONG)
                .valueSerializer(new GeobufEncoder.GeobufFeatureSerializer(12))
                .counterEnable()
                .make();

        tiles = db.createTreeSet("tiles")
                .serializer(BTreeKeySerializer.TUPLE3)
                .make();

        // bind the map by tile
        features.modificationListenerAdd((id, feat0, feat1) -> {
            // updates never change geometry, and there are no deletes
            if (feat0 != null) {
                return;
            }
            // figure out which z11 tiles this is part of
            Envelope e = feat1.geometry.getEnvelopeInternal();
            for (int x = lon2tile(e.getMinX(), ZOOM_LEVEL); x <= lon2tile(e.getMaxX(), ZOOM_LEVEL); x++) {
                for (int y = lat2tile(e.getMaxY(), ZOOM_LEVEL); y <= lat2tile(e.getMinY(), ZOOM_LEVEL); y++) {
                    tiles.add(new Fun.Tuple3(x, y, feat1.numericId));
                }
            }
        });
    }

    public void add(GeobufFeature feature) {
        if (this.features.containsKey(feature.numericId))
            throw new IllegalArgumentException("ID " + feature.numericId + " already present in store");
        this.features.put(feature.numericId, feature);

        if (this.features.size() % 10000 == 0)
            LOG.info("Loaded {} features", this.features.size());
    }

    /** Get the longitude of a particular tile */
    public static int lon2tile (double lon, int zoom) {
        // recenter
        lon += 180;

        // scale
        return (int) (lon * Math.pow(2, zoom) / 360);
    }

    public void close () {
        db.close();
    }

    /** Get the latitude of a particular tile */
    public static int lat2tile (double lat, int zoom) {
        // http://wiki.openstreetmap.org/wiki/Slippy_map_tilenames
        lat = Math.toRadians(lat);
        lat = Math.log(Math.tan(lat) + 1 / Math.cos(lat));

        return (int) ((1 - lat / Math.PI) / 2 * Math.pow(2, zoom));
    }

    /** Write GeoBuf tiles to a directory */
    public void writeTiles (File file) throws IOException {
        writeTilesInternal((x, y) -> {
            // write out the features
            File dir = new File(file, "" + x);
            File out = new File(dir, y + ".pbf.gz");
            dir.mkdirs();
            return new FileOutputStream(out);
        });
    }

    /** Write GeoBuf tiles to S3 */
    public void writeTilesToS3 (String bucketName) throws IOException {
        // Upload on a single separate thread, overlapping the upload of one tile with the encoding of the next.
        // The queue of one task and the caller-runs policy limit memory usage to three tile buffers: one uploading,
        // one queued, and one being produced (either encoded or uploaded by the caller when the queue is full).
        ExecutorService executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(1), new ThreadPoolExecutor.CallerRunsPolicy());

        // Retain the pending result of every upload, keyed on the S3 object key for error reporting.
        // Using submit instead of execute ensures failures are recorded in the Future instance.
        // Otherwise behavior depends on which thread runs the task (consider caller-runs rejection policy).
        List<Map.Entry<String, Future<?>>> uploads = new ArrayList<>();

        S3Client s3 = S3Client.create();
        try {
            writeTilesInternal((x, y) -> new ByteArrayOutputStream() {
                // The AWS S3 SDK requires the content length before uploading. We buffer each
                // gzipped tile in a BAOS and override the close method to upload it when closed.
                @Override
                public void close () {
                    String key = String.format("%d/%d.pbf.gz", x, y);
                    PutObjectRequest request = PutObjectRequest.builder()
                            .bucket(bucketName)
                            .key(key)
                            .contentType("application/gzip")
                            .build();
                    byte[] tile = toByteArray();
                    uploads.add(Map.entry(key,
                            executor.submit(() -> s3.putObject(request, RequestBody.fromBytes(tile)))));
                }
            });
        } finally {
            // allow the JVM to exit
            executor.shutdown();
            try {
                executor.awaitTermination(1, TimeUnit.HOURS);
            } catch (InterruptedException e) {
                LOG.error("Interrupted while waiting for S3 uploads to finish");
                // Leave the thread's interrupted status set so Future.get calls below fail fast.
                Thread.currentThread().interrupt();
            }
        }
        // The executor has terminated. Every Future is complete, calling get on them will not block.
        int failedUploads = 0;
        for (Map.Entry<String, Future<?>> upload : uploads) {
            try {
                upload.getValue().get();
            } catch (ExecutionException e) {
                failedUploads += 1;
                LOG.error("Uploading tile {} failed: {}", upload.getKey(), e.getCause().toString());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting for S3 uploads to finish.", e);
            }
        }
        if (failedUploads > 0) {
            throw new IOException(failedUploads + " of " + uploads.size() + " tile uploads to S3 failed.");
        }
    }

    /**
     * Generic write tiles function. Calls the supplied function with x and y indices to get an
     * output stream, which this method will close when all relevant features have been added.
     * The name is suffixed with Internal to avoid confusing Java interactions between lambdas and
     * overloaded functions.
     */
    private void writeTilesInternal(TileOutputStreamProducer outputStreamForTile) throws IOException {
        int tileCount = 0;
        List<GeobufFeature> featuresThisTile = new ArrayList<>();
        // The set is sorted, so all entries for one tile are consecutive. Gather features until the next entry
        // belongs to a different tile or there is no next entry, then write the finished tile out. Looking ahead
        // rather than comparing against the previous entry ensures the final tile is also written.
        PeekingIterator<Fun.Tuple3<Integer, Integer, Long>> iterator = Iterators.peekingIterator(tiles.iterator());
        while (iterator.hasNext()) {
            Fun.Tuple3<Integer, Integer, Long> entry = iterator.next();
            featuresThisTile.add(features.get(entry.c));
            boolean tileFinished = !iterator.hasNext()
                    || !entry.a.equals(iterator.peek().a)
                    || !entry.b.equals(iterator.peek().b);
            if (tileFinished) {
                LOG.debug("x: {}, y: {}, {} features", entry.a, entry.b, featuresThisTile.size());
                GeobufEncoder enc = new GeobufEncoder(
                        new GZIPOutputStream(new BufferedOutputStream(outputStreamForTile.apply(entry.a, entry.b))),
                        PRECISION);
                enc.writeFeatureCollection(featuresThisTile);
                enc.close();
                featuresThisTile.clear();
                tileCount++;
            }
        }
        LOG.info("Wrote {} tiles", tileCount);
    }

    /** get a feature */
    public GeobufFeature get(long id) {
        // protective copy, don't get entangled in mapdb async serialization.
        return features.get(id).clone();
    }

    /** put a feature that already exists */
    public void put (GeobufFeature feat) {
        if (!features.containsKey(feat.numericId))
            throw new IllegalArgumentException("Feature does not exist in database!");

        features.put(feat.numericId, feat);
    }

    @FunctionalInterface
    private interface TileOutputStreamProducer {
        public OutputStream apply (int x, int y) throws IOException;
    }
}
