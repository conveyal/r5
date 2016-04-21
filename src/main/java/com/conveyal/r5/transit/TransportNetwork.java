package com.conveyal.r5.transit;

import com.conveyal.osmlib.OSM;
import com.conveyal.r5.analyst.WebMercatorGridPointSet;
import com.conveyal.r5.analyst.scenario.Scenario;
import com.conveyal.r5.common.JsonUtilities;
import com.conveyal.r5.point_to_point.builder.TNBuilderConfig;
import com.conveyal.r5.profile.Mode;
import com.vividsolutions.jts.geom.Envelope;
import org.nustaq.serialization.FSTObjectInput;
import org.nustaq.serialization.FSTObjectOutput;
import com.conveyal.r5.streets.LinkedPointSet;
import com.conveyal.r5.streets.StreetLayer;
import com.conveyal.r5.streets.StreetRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.time.ZoneId;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * This is a completely new replacement for Graph, Router etc.
 * It uses a lot less object pointers and can be built, read, and written orders of magnitude faster.
 * @author abyrd
 */
public class TransportNetwork implements Serializable {

    private static final Logger LOG = LoggerFactory.getLogger(TransportNetwork.class);

    public StreetLayer streetLayer;

    public TransitLayer transitLayer;

    private WebMercatorGridPointSet gridPointSet;

    static final String BUILDER_CONFIG_FILENAME = "build-config.json";

    public void write (OutputStream stream) throws IOException {
        LOG.info("Writing transport network...");
        FSTObjectOutput out = new FSTObjectOutput(stream);
        out.writeObject(this, TransportNetwork.class);
        out.close();
        LOG.info("Done writing.");
    }

    public static TransportNetwork read (InputStream stream) throws Exception {
        LOG.info("Reading transport network...");
        FSTObjectInput in = new FSTObjectInput(stream);
        TransportNetwork result = (TransportNetwork) in.readObject(TransportNetwork.class);
        in.close();
        result.rebuildTransientIndexes();
        LOG.info("Done reading.");
        return result;
    }

    public void rebuildTransientIndexes() {
        streetLayer.buildEdgeLists();
        streetLayer.indexStreets();
        transitLayer.rebuildTransientIndexes();
        transitLayer.buildStopTrees();
    }

    /**
     * Test main method: Round-trip serialize the transit layer and test its speed after deserialization.
     */
    public static void main (String[] args) {
        // TransportNetwork transportNetwork = TransportNetwork.fromFiles(args[0], args[1]);
        TransportNetwork transportNetwork = TransportNetwork.fromDirectory(new File("."));
        try {
            OutputStream outputStream = new BufferedOutputStream(new FileOutputStream("network.dat"));
            transportNetwork.write(outputStream);
            outputStream.close();
            // Be careful to release the original reference to be sure to have heap space
            transportNetwork = null;
            InputStream inputStream = new BufferedInputStream(new FileInputStream("network.dat"));
            transportNetwork = TransportNetwork.read(inputStream);
            inputStream.close();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        transportNetwork.testRouting();
        // transportNetwork.streetLayer.testRouting(false, transitLayer);
        // transportNetwork.streetLayer.testRouting(true, transitLayer);
    }

    /** Legacy method to load from a single GTFS file */
    public static TransportNetwork fromFiles (String osmSourceFile, String gtfsSourceFile, TNBuilderConfig tnBuilderConfig) {
        return fromFiles(osmSourceFile, Arrays.asList(gtfsSourceFile), tnBuilderConfig);
    }

    /**
     * OSM PBF files are fragments of a single global database with a single namespace. Therefore it is valid to load
     * more than one PBF file into a single OSM storage object. However they might be from different points in time,
     * so it may be cleaner to just map one PBF file to one OSM object.
     *
     * On the other hand, GTFS feeds each have their own namespace. Each GTFS object is for one specific feed, and this
     * distinction should be maintained for various reasons. However, we use the GTFS IDs only for reference, so it doesn't
     * really matter, particularly for analytics.
     */
    public static TransportNetwork fromFiles (String osmSourceFile, List<String> gtfsSourceFiles, TNBuilderConfig tnBuilderConfig) {

        System.out.println("Summarizing builder config: " + BUILDER_CONFIG_FILENAME);
        System.out.println(tnBuilderConfig);
        File dir = new File(osmSourceFile).getParentFile();
        // Load OSM data into MapDB
        OSM osm = new OSM(new File(dir,"osm.mapdb").getPath());
        osm.intersectionDetection = true;
        osm.readFromFile(osmSourceFile);

        // Make street layer from OSM data in MapDB
        StreetLayer streetLayer = new StreetLayer(tnBuilderConfig);
        streetLayer.loadFromOsm(osm);
        osm.close();

        // The street index is needed for associating transit stops with the street network
        // and for associating bike shares with the street network
        streetLayer.indexStreets();

        if (tnBuilderConfig.bikeRentalFile != null) {
            streetLayer.associateBikeSharing(tnBuilderConfig, 500);
        }

        // Load transit data TODO remove need to supply street layer at this stage
        TransitLayer transitLayer = TransitLayer.fromGtfs(gtfsSourceFiles);

        // The street index is needed for associating transit stops with the street network.
        streetLayer.indexStreets();
        streetLayer.associateStops(transitLayer);
        // Edge lists must be built after all inter-layer linking has occurred.
        streetLayer.buildEdgeLists();
        transitLayer.rebuildTransientIndexes();
        transitLayer.buildStopTrees();

        // Create transfers
        new TransferFinder(transitLayer, streetLayer, 1000).findTransfers();

        // Create and serialize a transport network
        TransportNetwork transportNetwork = new TransportNetwork();
        transportNetwork.streetLayer = streetLayer;
        transportNetwork.transitLayer = transitLayer;

        return transportNetwork;
    }

    public static TransportNetwork fromDirectory (File directory) {
        File osmFile = null;
        List<String> gtfsFiles = new ArrayList<>();
        TNBuilderConfig builderConfig = null;
        //This can exit program if json file has errors.
        builderConfig = loadJson(new File(directory, BUILDER_CONFIG_FILENAME));
        for (File file : directory.listFiles()) {
            switch (InputFileType.forFile(file)) {
                case GTFS:
                    LOG.info("Found GTFS file {}", file);
                    gtfsFiles.add(file.getAbsolutePath());
                    break;
                case OSM:
                    LOG.info("Found OSM file {}", file);
                    if (osmFile == null) {
                        osmFile = file;
                    } else {
                        LOG.warn("Can only load one OSM file at a time.");
                    }
                    break;
                case DEM:
                    LOG.warn("DEM file '{}' not yet supported.", file);
                    break;
                case OTHER:
                    LOG.warn("Skipping non-input file '{}'", file);
            }
        }
        return fromFiles(osmFile.getAbsolutePath(), gtfsFiles, builderConfig);
    }

    /**
     * Open and parse the JSON file at the given path into a Jackson JSON tree. Comments and unquoted keys are allowed.
     * Returns default config if the file does not exist,
     * Returns null if the file contains syntax errors or cannot be parsed for some other reason.
     * <p>
     * We do not require any JSON config files to be present because that would get in the way of the simplest
     * rapid deployment workflow. Therefore we return default config if file does not exist.
     */
    static TNBuilderConfig loadJson(File file) {
        try (FileInputStream jsonStream = new FileInputStream(file)) {
            TNBuilderConfig config = JsonUtilities.objectMapper
                .readValue(jsonStream, TNBuilderConfig.class);
            config.fillPath(file.getParent());
            LOG.info("Found and loaded JSON configuration file '{}'", file);
            return config;
        } catch (FileNotFoundException ex) {
            LOG.info("File '{}' is not present. Using default configuration.", file);
            return TNBuilderConfig.defaultConfig();
        } catch (Exception ex) {
            LOG.error("Error while parsing JSON config file '{}': {}", file, ex.getMessage());
            System.exit(42); // probably "should" be done with an exception
            return null;
        }
    }

    /**
     * Opens OSM MapDB database if it exists
     * Otherwise it prints a warning
     *
     * OSM MapDB is used for names of streets. Since they are needed only in display of paths.
     * They aren't saved in street layer.
     * @param file
     */
    public void readOSM(File file) {
        if (file.exists()) {
            streetLayer.openOSM(file);
        } else {
            LOG.info("osm.mapdb doesn't exist in graph folder. This means that street names won't be shown");
        }
    }

    /**
     * Represents the different types of files that might be present in a router / graph build directory.
     * We want to detect even those that are not graph builder inputs so we can effectively warn when unrecognized file
     * types are present. This helps point out when config files have been misnamed.
     */
    private static enum InputFileType {
        GTFS, OSM, DEM, CONFIG, OUTPUT, OTHER;
        public static InputFileType forFile(File file) {
            String name = file.getName();
            if (name.endsWith(".zip")) {
                try {
                    ZipFile zip = new ZipFile(file);
                    ZipEntry stopTimesEntry = zip.getEntry("stop_times.txt");
                    zip.close();
                    if (stopTimesEntry != null) return GTFS;
                } catch (Exception e) { /* fall through */ }
            }
            if (name.endsWith(".pbf") || name.endsWith(".vex")) return OSM;
            if (name.endsWith(".tif") || name.endsWith(".tiff")) return DEM; // Digital elevation model (elevation raster)
            if (name.endsWith("network.dat")) return OUTPUT;
            return OTHER;
        }
    }

    /**
     * Test combined street and transit routing.
     */
    public void testRouting () {
        LOG.info("Street and transit routing from random street corners...");
        StreetRouter streetRouter = new StreetRouter(streetLayer);
        streetRouter.distanceLimitMeters = 1500;
        TransitRouter transitRouter = new TransitRouter(transitLayer);
        long startTime = System.currentTimeMillis();
        final int N = 1_000;
        final int nStreetIntersections = streetLayer.getVertexCount();
        Random random = new Random();
        for (int n = 0; n < N; n++) {
            // Do one street search around a random origin and destination, initializing the transit router
            // with the stops that were reached.
            int from = random.nextInt(nStreetIntersections);
            int to = random.nextInt(nStreetIntersections);
            streetRouter.setOrigin(from);
            streetRouter.route();
            streetRouter.setOrigin(to);
            streetRouter.route();
            transitRouter.reset();
            transitRouter.setOrigins(streetRouter.getReachedStops(), 8 * 60 * 60);
            transitRouter.route();
            if (n != 0 && n % 100 == 0) {
                LOG.info("    {}/{} searches", n, N);
            }
        }
        double eTime = System.currentTimeMillis() - startTime;
        LOG.info("average response time {} msec", eTime / N);
    }

    /**
     * @return an efficient implicit grid PointSet for this TransportNetwork.
     */
    public WebMercatorGridPointSet getGridPointSet() {
        if (this.gridPointSet == null) {
            synchronized (this) {
                if (this.gridPointSet == null) {
                    this.gridPointSet = new WebMercatorGridPointSet(this);
                }
            }
        }
        return this.gridPointSet;
    }

    /**
     * @return an efficient implicit grid PointSet for this TransportNetwork, pre-linked to the street layer.
     */
    public LinkedPointSet getLinkedGridPointSet() {
        // TODO don't hardwire walk mode
        return getGridPointSet().link(streetLayer, Mode.WALK);
    }

    //TODO: add transit stops to envelope
    public Envelope getEnvelope() {
        return streetLayer.getEnvelope();
    }

    /**
     * Gets timezone of this transportNetwork
     *
     * If transitLayer exists returns transitLayer timezone otherwise GMT
     *
     * It is never null
     * @return TransportNetwork timezone
     */
    public ZoneId getTimeZone() {
        if (transitLayer == null) {
            LOG.warn("TransportNetwork transit layer isn't loaded; API request times will be interpreted as GMT.");
            return ZoneId.of("GMT");
        } else if (transitLayer.timeZone == null) {
            LOG.error(
                "TransportNetwork transit layer is loaded but timezone is unknown; API request times will be interpreted as GMT.");
            return ZoneId.of("GMT");
        } else {
            return transitLayer.timeZone;
        }
    }

    /**
     * Apply the given scenario to this TransportNetwork, copying any elements that are modified to leave the original
     * unscathed. The scenario may be null or empty, in which case this method is a no-op.
     */
    public TransportNetwork applyScenario (Scenario scenario) {
        if (scenario == null || scenario.modifications.isEmpty()) {
            return this;
        }
        return scenario.applyToTransportNetwork(this);
    }

    /**
     * Currently this is intended for use by Modifications but not when building the network initially.
     * If this is being used in a non-destructive Modification, the caller must already have made protective copies of
     * all fields that will be modified.
     * Really we should use the same function for modifications and when initially creating the TransportNetwork. This
     * function would need to create the stop, link it to the street network, and make a stop tree for that stop.
     */
    public int addStop (String id, double lat, double lon) {
        int newStopIndex = transitLayer.getStopCount();
        int newStreetVertexIndex = streetLayer.getOrCreateVertexNear(lat, lon);
        transitLayer.stopIdForIndex.add(id); // TODO check for uniqueness
        transitLayer.streetVertexForStop.add(newStreetVertexIndex);
        // TODO stop tree, any other stop-indexed arrays or lists
        return newStopIndex;
    }

    /**
     * When applying a Modification that will change the street network as part of a Scenario, call this method to
     * make sure that the StreetLayer has been duplicated in such a way that it can be modified without affecting the
     * original base StreetLayer. Duplication adds an extra layer of indirection to most edge accesses.
     *
     * NOTE we should no longer need this because the TransportNetwork is cloned only once per Scenario, rather than
     * once per Modification. We should only revert to this method if it turns out that the extend-only copy of the
     * street network adds significant speed overhead. And even then, we might just accept that.
     */
    @Deprecated
    public void ensureExtendOnlyStreetLayer() {
        if (!streetLayer.isExtendOnlyCopy()) {
            streetLayer = streetLayer.extendOnlyCopy();
        }
    }

    /**
     * We want to apply Scenarios to TransportNetworks, yielding a new TransportNetwork without disrupting the original
     * one. The approach is to make a copy of the TransportNetwork, then apply all the Modifications in the Scenario
     * one by one to that same copy. Two very different modification strategies are used for the TransitLayer and the
     * StreetLayer.
     * The TransitLayer has a hierarchy of collections, from patterns to trips to stoptimes. We can
     * selectively copy-on-modify these collections without much impact on performance as long as they don't become too
     * large. This is somewhat inefficient but easy to reason about, considering we allow both additions and deletions.
     * We don't use clone() here with the expectation that it will be more clear and maintainable to show exactly
     * how each field is being copied.
     * On the other hand, the StreetLayer contains a few very large lists which would be wasteful to copy.
     * It is duplicated in such a way that it wraps the original lists, allowing them to be non-destructively extended.
     * There will be some performance hit from wrapping these lists, but it's probably completely negligible.
     * @return a semi-shallow copy of this TransportNetwork.
     */
    public TransportNetwork scenarioCopy() {
        TransportNetwork copy = new TransportNetwork();
        copy.gridPointSet = gridPointSet;
        copy.transitLayer = transitLayer.clone();
        copy.streetLayer = streetLayer.extendOnlyCopy();
        // Update bidirectional reference between StreetLayer and TransitLayer
        copy.streetLayer.linkedTransitLayer = copy.transitLayer;
        copy.transitLayer.linkedStreetLayer = copy.streetLayer;
        return copy;
    }

}
