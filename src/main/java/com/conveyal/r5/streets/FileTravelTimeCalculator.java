package com.conveyal.r5.streets;

import com.conveyal.r5.profile.ProfileRequest;
import com.conveyal.r5.profile.StreetMode;
import com.csvreader.CsvReader;
import com.google.cloud.storage.Blob;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public class FileTravelTimeCalculator implements Runnable, TravelTimeCalculator {
    private static final org.slf4j.Logger LOG = LoggerFactory.getLogger(FileTravelTimeCalculator.class);
    private final EdgeStore.DefaultTravelTimeCalculator delegateTravelTimeCalculator =
            new EdgeStore.DefaultTravelTimeCalculator();

    private Map<Integer, short[]> linkTravelTimes = new HashMap<>();
    /*
     The lock protects `linkTravelTimes`. During every query a read lock is acquired.

     Whenever there is an updated  congestion file:
     1. The write lock is acquired (to prevent the serving threads from trying to read the map).
     2. `linkTravelTimes` is deleted (to avoid having both the old and the new version of the map in memory
     simultaneously)
     3. A new version is read from a csv.
     5. The lock is released.
    */
    private final ReadWriteLock linkTravelTimes_m = new ReentrantReadWriteLock();
    private final Storage storage = StorageOptions.getDefaultInstance().getService();

    private String currentCongestPath = "x";  // Non-empty value triggers the first "freeflow" log message if meta is empty.

    private String bucket = "";
    private String metaFilePath = "toronto/street_edges/congestion/current";

    private final Thread updaterThread = new Thread(this);

    /**
     *  The class behaves differently, depending on the value of the parameter.
     *
     *  If the parameter is a local path, then the file at that path is read into `linkTravelTimes`.
     *
     *  If the parameter is a GCS path, than the contents of the file (let's call it a "meta" file) is treated as a
     *  path to a congestion file on GCS. The contents of the "meta" file is monitored, and whenever it is updated,
     *  `linkTravelTimes` is updated with the contents of the new congestion file.
     *
     * @param path Either:
     *             - path to the local file with congestion data.
     *             - path on GCS to a text file, containing the path on GCS to a file with congestion data.
     */
    public FileTravelTimeCalculator(String path) {
        if (path.startsWith("gs://")) {
            LOG.warn("Using metafile at {}", path);
            Matcher match = Pattern.compile("gs://(.*?)/(.*)").matcher(path);
            if (match.find()) {
                bucket = match.group(1);
                metaFilePath = match.group(2);
            } else {
                LOG.error("Failed to parse {}", path);
            }
            updaterThread.start();
        } else {
            linkTravelTimes = readTravelTimes(new File(path));
        }
    }

    @Override
    public float getTravelTimeMilliseconds(EdgeStore.Edge edge, int durationSeconds, StreetMode streetMode,
                                           ProfileRequest req) {
        if (linkTravelTimes != null && streetMode == StreetMode.CAR) {
            short[] speeds = linkTravelTimes.get(edge.getEdgeIndex());
            if (speeds != null) {
                int currentTimeSeconds = req.fromTime + durationSeconds;
                int timebinIndex = (currentTimeSeconds / (60 * 15)) % (24 * 4);
                double speedms = speeds[timebinIndex] / 3.6;
                return (float) (edge.getLengthM() / speedms);
            }
        }
        return delegateTravelTimeCalculator.getTravelTimeMilliseconds(edge, durationSeconds, streetMode, req);
    }

    @Override
    public Lock startRequest() {
        Lock res = linkTravelTimes_m.readLock();
        res.lock();
        return res;
    }

    @Override
    public void finishRequest(Lock l) {
        l.unlock();
    }

    private static Map<Integer, short[]> readTravelTimes(File file) {
        Map<Integer, short[]> res = new HashMap<>();

        LOG.warn("Processing {}", file.toString());
        try (InputStream is = new FileInputStream(file)) {
            CsvReader reader = new CsvReader(is, ',', Charset.forName("UTF-8"));
            reader.readHeaders();
            while (reader.readRecord()) {
                int edgeId = Integer.parseInt(reader.get("edgeId"));
                int[] speeds = IntStream.range(0, 24).mapToObj(Integer::toString).flatMap(hour -> {
                    try {
                        return Stream.of(Short.parseShort(reader.get(hour + "h_1")),
                                Short.parseShort(reader.get(hour + "h_2")), Short.parseShort(reader.get(hour + "h_3")),
                                Short.parseShort(reader.get(hour + "h_4")));
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }).mapToInt(v -> v).toArray();
                // TODO(sindelar): temporary hack to decrease the memory footprint for congestion
                short shortSpeeds[] = new short[speeds.length];
                for (int i = 0; i < speeds.length; i++) {
                    shortSpeeds[i] = (short) speeds[i];
                }
                res.put(edgeId, shortSpeeds);
            }
        } catch (IOException ex) {
            throw new RuntimeException("Exception while loading travel times.");
        }
        LOG.warn("Done.");

        return res;
    }

    private void updateCongest(String newPath) throws IOException {
        LOG.warn("Loading a new congestion file: {}", newPath);
        Blob blob = storage.get("city_data", newPath);
        if (blob == null) {
            LOG.warn("Congestion file not found at {}", newPath);
            return;
        }

        Path file = Files.createTempFile("congest-", ".csv");
        LOG.warn("Downloading to {}", file.toString());
        blob.downloadTo(file);

        Lock l = linkTravelTimes_m.writeLock();
        l.lock();
        linkTravelTimes = null;
        linkTravelTimes = readTravelTimes(file.toFile());
        l.unlock();
    }

    // Check whether the congestion file has changed. If it has, update the `linkTravelTimes.
    @Override
    public void run() {
        while (true) {
            try {
                Blob blob = storage.get("city_data", metaFilePath);
                if (blob == null) {
                    LOG.error("Meta file not found at ", metaFilePath);
                    return;
                }
                String newCongestPath = new String(blob.getContent()).trim();

                if (!currentCongestPath.equals(newCongestPath)) {
                    if (newCongestPath.isEmpty()) {
                        LOG.warn("No congestion file specified in metafile. Switching to freeflow.");
                        Lock l = linkTravelTimes_m.writeLock();
                        l.lock();
                        linkTravelTimes = null;
                        l.unlock();
                        currentCongestPath = "";
                        continue;
                    }

                    updateCongest(newCongestPath);
                    currentCongestPath = newCongestPath;
                }
            } catch (Exception e) {
                LOG.error("While downloading congestion file: {}", e.getMessage());
            } finally {
                try {
                    Thread.sleep(60 * 1000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }
    }
}
