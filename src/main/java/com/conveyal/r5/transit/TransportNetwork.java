package com.conveyal.r5.transit;

import com.conveyal.gtfs.GTFSFeed;
import com.conveyal.osmlib.OSM;
import com.conveyal.r5.analyst.LinkageCache;
import com.conveyal.r5.analyst.WebMercatorGridPointSet;
import com.conveyal.r5.analyst.error.TaskError;
import com.conveyal.r5.analyst.fare.InRoutingFareCalculator;
import com.conveyal.r5.analyst.scenario.Scenario;
import com.conveyal.r5.common.JsonUtilities;
import com.conveyal.r5.kryo.KryoNetworkSerializer;
import com.conveyal.r5.point_to_point.builder.TNBuilderConfig;
import com.conveyal.r5.profile.StreetMode;
import com.conveyal.r5.streets.StreetLayer;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hashing;
import com.google.common.io.Files;
import org.locationtech.jts.geom.Envelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.Serializable;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * This is a completely new replacement for Graph, Router etc.
 * It uses a lot less object pointers and can be built, read, and written orders of magnitude faster.
 * @author abyrd
 */
public class TransportNetwork implements Serializable {

    private static final Logger LOG = LoggerFactory.getLogger(TransportNetwork.class);

    /**
     * Serializing this cache serializes its settings, but only its "unevictable" contents. This is intentional.
     * TransportNetwork might not really be the right place for this though, it's part of the routing machinery.
     * When we make a scenario copy of the network, it seems wrong that this TransportNetwork-level field is
     * cloned. We could have fullExtentPointSet and fullExtentWalkLinkage fields which go into a worker-wide cache.
     * However it's also convenient to have this cache garbage collected when the TransportNetwork is GC'ed.
     */
    public LinkageCache linkageCache = new LinkageCache();

    public StreetLayer streetLayer;

    public TransitLayer transitLayer;

    /**
     * This stores any number of lightweight scenario networks built upon the current base network.
     * FIXME that sounds like a memory leak, should be a WeighingCache or at least size-limited.
     * A single network cache at the top level could store base networks and scenarios since they all have globally
     * unique IDs. A hierarchical cache does have the advantage of evicting all the scenarios with the associated
     * base network, which keeps the references in the scenarios from holding on to the base network. But considering
     * that we have never started evicting networks (other than for a "cache" of one element) this might be getting
     * ahead of ourselves.
     */
    public transient Map<String, TransportNetwork> scenarios = new HashMap<>();

    /**
     * A grid point set that covers the full extent of this transport network.
     * This unlinked GridPointSet is not specific to any mode of travel, it's just a set of points.
     * Once created, this point set is serialized along with the network, and its WALK linkage to the street
     * network and associated cost tables are serialized and reloaded via the unevictable map in the LinkageCache.
     * This makes startup much faster.
     */
    public WebMercatorGridPointSet fullExtentGridPointSet;

    /**
     * A string uniquely identifying the contents of this TransportNetwork in the space of TransportNetworks.
     * When no scenario has been applied, this field will contain the original base networkId.
     * When a scenario has modified a base network to produce this network, this field will be changed to the
     * scenario ID. This allows proper caching of downstream data and results: we need a way to know what information
     * is in the network independent of object identity, and after a round trip through serialization.
     */
    public String scenarioId = null;

    public static final String BUILDER_CONFIG_FILENAME = "build-config.json";

    public InRoutingFareCalculator fareCalculator;

    /** Non-fatal warnings encountered when applying the scenario, null on a base network */
    public transient List<TaskError> scenarioApplicationWarnings;

    /** Information about the effects of apparently correct scenario application, null on a base network */
    public transient List<TaskError> scenarioApplicationInfo;

    /**
     * Build some simple derived index tables that are not serialized with the network.
     * Distance tables and street spatial indexes are now serialized with the network.
     */
    public void rebuildTransientIndexes() {
        streetLayer.buildEdgeLists();
        streetLayer.indexStreets();
        transitLayer.rebuildTransientIndexes();
    }


    /** Create a TransportNetwork from gtfs-lib feeds */
    public static TransportNetwork fromFeeds (String osmSourceFile, List<GTFSFeed> feeds, TNBuilderConfig config) {
        return fromFiles(osmSourceFile, null, feeds, config);
    }

    /** Legacy method to load from a single GTFS file */
    public static TransportNetwork fromFiles (String osmSourceFile, String gtfsSourceFile, TNBuilderConfig tnBuilderConfig) throws DuplicateFeedException {
        return fromFiles(osmSourceFile, Arrays.asList(gtfsSourceFile), tnBuilderConfig);
    }

    /**
     * It would seem cleaner to just have two versions of this function, one which takes a list of strings and converts
     * it to a list of feeds, and one that just takes a list of feeds directly. However, this would require loading all
     * the feeds into memory simulataneously, which shouldn't be so bad with mapdb-based feeds, but it's still not great
     * (due to caching etc.)
     */
    private static TransportNetwork fromFiles (String osmSourceFile, List<String> gtfsSourceFiles, List<GTFSFeed> feeds,
                                               TNBuilderConfig tnBuilderConfig) throws DuplicateFeedException {

        System.out.println("Summarizing builder config: " + BUILDER_CONFIG_FILENAME);
        System.out.println(tnBuilderConfig);

        // Create a transport network to hold the street and transit layers
        TransportNetwork transportNetwork = new TransportNetwork();

        // Load OSM data into MapDB
        OSM osm = new OSM(osmSourceFile + ".mapdb");
        osm.intersectionDetection = true;
        osm.readFromFile(osmSourceFile);

        // Make street layer from OSM data in MapDB
        StreetLayer streetLayer = new StreetLayer(tnBuilderConfig);
        transportNetwork.streetLayer = streetLayer;
        streetLayer.parentNetwork = transportNetwork;
        streetLayer.loadFromOsm(osm);
        // Note that if load fails, the OSM mapdb might not be closed leaving a corrupted file.
        // We should probably try to delete the file when exceptions occur.
        osm.close();

        // The street index is needed for associating transit stops with the street network
        // and for associating bike shares with the street network
        streetLayer.indexStreets();

        if (tnBuilderConfig.bikeRentalFile != null) {
            streetLayer.associateBikeSharing(tnBuilderConfig);
        }

        // Load transit data TODO remove need to supply street layer at this stage
        TransitLayer transitLayer = new TransitLayer();

        if (feeds != null) {
            for (GTFSFeed feed : feeds) {
                transitLayer.loadFromGtfs(feed);
            }
        } else {
            for (String feedFile: gtfsSourceFiles) {
                GTFSFeed feed = GTFSFeed.fromFile(feedFile);
                transitLayer.loadFromGtfs(feed);
                feed.close();
            }
        }
        transportNetwork.transitLayer = transitLayer;
        transitLayer.parentNetwork = transportNetwork;
        // transitLayer.summarizeRoutesAndPatterns();

        // The street index is needed for associating transit stops with the street network.
        // FIXME indexStreets is called three times: in StreetLayer::loadFromOsm, just after loading the OSM, and here
        streetLayer.indexStreets();
        streetLayer.associateStops(transitLayer);
        // Edge lists must be built after all inter-layer linking has occurred.
        streetLayer.buildEdgeLists();
        transitLayer.rebuildTransientIndexes();

        // Create transfers
        new TransferFinder(transportNetwork).findTransfers();
        new TransferFinder(transportNetwork).findParkRideTransfer();

        transportNetwork.fareCalculator = tnBuilderConfig.analysisFareCalculator;

        if (transportNetwork.fareCalculator != null) transportNetwork.fareCalculator.transitLayer = transitLayer;

        return transportNetwork;
    }

    /**
     * OSM PBF files are fragments of a single global database with a single namespace. Therefore it is valid to load
     * more than one PBF file into a single OSM storage object. However they might be from different points in time, so
     * it may be cleaner to just map one PBF file to one OSM object.
     * On the other hand, GTFS feeds each have their own namespace. Each GTFS object is for one specific feed, and this
     * distinction should be maintained for various reasons. However, we use the GTFS IDs only for reference, so it
     * doesn't really matter, particularly for analytics.
     */
    public static TransportNetwork fromFiles (String osmFile, List<String> gtfsFiles, TNBuilderConfig config) {
        return fromFiles(osmFile, gtfsFiles, null, config);
    }

    public static TransportNetwork fromDirectory (File directory) throws DuplicateFeedException {
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
        if (osmFile == null) {
            LOG.error("An OSM PBF file is required to build a network.");
            return null;
        } else {
            return fromFiles(osmFile.getAbsolutePath(), gtfsFiles, builderConfig);
        }
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
     * For Analysis purposes, build an efficient implicit grid PointSet for this TransportNetwork. Then, for any modes
     * supplied, we also build a linkage that is held permanently in the GridPointSet. This method is called when a
     * network is first built.
     * The resulting grid PointSet will cover the entire street network layer of this TransportNetwork, which should
     * include every point we can route from or to. Any other destination grid (for the same mode, walking) can be made
     * as a subset of this one since it includes every potentially accessible point.
     */
    public void rebuildLinkedGridPointSet(StreetMode... modes) {
        if (fullExtentGridPointSet != null) {
            throw new RuntimeException("Linked grid pointset was built more than once.");
        }
        fullExtentGridPointSet = new WebMercatorGridPointSet(this);
        for (StreetMode mode : modes) {
            linkageCache.buildUnevictableLinkage(fullExtentGridPointSet, streetLayer, mode);
        }
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
     * We want to apply Scenarios to TransportNetworks, yielding a new TransportNetwork without disrupting the original
     * one. The approach is to make a copy of the TransportNetwork, then apply all the Modifications in the Scenario one
     * by one to that same copy. Two very different modification strategies are used for the TransitLayer and the
     * StreetLayer. The TransitLayer has a hierarchy of collections, from patterns to trips to stoptimes. We can
     * selectively copy-on-modify these collections without much impact on performance as long as they don't become too
     * large. This is somewhat inefficient but easy to reason about, considering we allow both additions and deletions.
     * We don't use clone() here with the expectation that it will be more clear and maintainable to show exactly how
     * each field is being copied. On the other hand, the StreetLayer contains a few very large lists which would be
     * wasteful to copy. It is duplicated in such a way that it wraps the original lists, allowing them to be
     * non-destructively extended. There will be some performance hit from wrapping these lists, but it's probably
     * negligible.
     *
     * @return a copy of this TransportNetwork that is partly shallow and partly deep.
     */
    public TransportNetwork scenarioCopy(Scenario scenario) {
        // Maybe we should be using clone() here but TransportNetwork has very few fields and most are overwritten.
        TransportNetwork copy = new TransportNetwork();
        // It is important to set this before making the clones of the street and transit layers below.
        copy.scenarioId = scenario.id;
        copy.fullExtentGridPointSet = this.fullExtentGridPointSet;
        copy.transitLayer = this.transitLayer.scenarioCopy(copy, scenario.affectsTransitLayer());
        copy.streetLayer = this.streetLayer.scenarioCopy(copy, scenario.affectsStreetLayer());
        copy.fareCalculator = this.fareCalculator;
        copy.linkageCache = this.linkageCache; // <-- weirdness, TODO get this out of the TransportNetwork
        return copy;
    }

    /**
     * FIXME why is this a long when crc32 returns an int?
     * @return a checksum of the graph, for use in verifying whether it changed or remained the same after
     * some operation.
     */
    public long checksum () {
        LOG.info("Calculating transport network checksum...");
        try {
            File tempFile = File.createTempFile("r5-network-checksum-", ".dat");
            tempFile.deleteOnExit();
            KryoNetworkSerializer.write(this, tempFile);
            HashCode crc32 = Files.hash(tempFile, Hashing.crc32());
            tempFile.delete();
            LOG.info("Network CRC is {}", crc32.hashCode());
            return crc32.hashCode();
        } catch (IOException e) {
            throw new RuntimeException();
        }
    }

}
