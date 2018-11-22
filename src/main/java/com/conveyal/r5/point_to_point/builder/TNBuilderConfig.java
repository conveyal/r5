package com.conveyal.r5.point_to_point.builder;

import com.conveyal.r5.analyst.fare.InRoutingFareCalculator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.File;

/**
 * These are parameters that when changed, necessitate a Graph rebuild.
 * They are distinct from the RouterParameters which can be applied to a pre-built graph or on the fly at runtime.
 * Eventually both classes may be initialized from the same config file so make sure there is no overlap
 * in the JSON keys used.
 *
 * These used to be command line parameters, but there were getting to be too many of them and besides, we want to
 * allow different graph builder configuration for each Graph.
 * <p>
 * TODO maybe have only one giant config file and just annotate the parameters to indicate which ones trigger a rebuild
 * ...or just feed the same JSON tree to two different classes, one of which is the build configuration and the other is the router configuration.
 */
public class TNBuilderConfig {
    public static double DEFAULT_SUBWAY_ACCESS_TIME = 2.0; // minutes

    /**
     * Generates nice HTML report of Graph errors/warnings (annotations). They are stored in the same location as the graph.
     */
    public final boolean htmlAnnotations;

    /**
     * If number of annotations is larger then specified number annotations will be split in multiple files.
     * Since browsers have problems opening large HTML files.
     */
    public final int maxHtmlAnnotationsPerFile;

    /**
     * Include all transit input files (GTFS) from scanned directory.
     */
    public final boolean transit;

    /**
     * Create direct transfer edges from transfers.txt in GTFS, instead of based on distance.
     */
    public final boolean useTransfersTxt;

    /**
     * Link GTFS stops to their parent stops.
     */
    public final boolean parentStopLinking;

    /**
     * Create direct transfers between the constituent stops of each parent station.
     */
    public final boolean stationTransfers;

    /**
     * Minutes necessary to reach stops served by trips on routes of route_type=1 (subway) from the street.
     * Perhaps this should be a runtime router parameter rather than a graph build parameter.
     */
    public final double subwayAccessTime;

    /**
     * Include street input files (OSM/PBF).
     */
    public final boolean streets;

    /**
     * Embed the Router config in the graph, which allows it to be sent to a server fully configured over the wire.
     */
    public final boolean embedRouterConfig;

    /**
     * Perform visibility calculations on OSM areas (these calculations can be time consuming).
     */
    public final boolean areaVisibility;

    /**
     * Based on GTFS shape data, guess which OSM streets each bus runs on to improve stop linking.
     */
    public final boolean matchBusRoutesToStreets;

    /**
     * Download US NED elevation data and apply it to the graph.
     */
    public final boolean fetchElevationUS;

    /** If specified, download NED elevation tiles from the given AWS S3 bucket. */
    //public final S3BucketConfig elevationBucket;

    /**
     * A specific fares service to use.
     */
    //public final FareServiceFactory fareServiceFactory;

    /**
     * Specifier for way speeds
     */
    public final SpeedConfig speeds;

    /**
     * A custom OSM namer to use.
     */
    //public final CustomNamer customNamer;

    /**
     * Whether bike rental stations should be loaded from OSM, rather than periodically dynamically pulled from APIs.
     */
    public final boolean staticBikeRental;

    /**
     * Whether we should create car P+R stations from OSM data.
     */
    public final boolean staticParkAndRide;

    /**
     * Whether we should create bike P+R stations from OSM data.
     */
    public final boolean staticBikeParkAndRide;

    /**
     * Path to bikeRental file currently only XML is support and file needs to be in same folder as OSM files
     */
    public String bikeRentalFile;

    /** The fare calculator for analysis */
    public InRoutingFareCalculator analysisFareCalculator;

    public TNBuilderConfig() {
        htmlAnnotations = false;
        maxHtmlAnnotationsPerFile = 1000;
        transit = true;
        useTransfersTxt = false;
        parentStopLinking = false;
        stationTransfers = false;
        subwayAccessTime = DEFAULT_SUBWAY_ACCESS_TIME;
        streets = true;
        embedRouterConfig = true;
        areaVisibility = false;
        matchBusRoutesToStreets = false;
        fetchElevationUS = false;
        staticBikeRental = false;
        staticParkAndRide = true;
        staticBikeParkAndRide = false;
        bikeRentalFile = null;
        speeds = SpeedConfig.defaultConfig();
        analysisFareCalculator = null;
    }

    public static TNBuilderConfig defaultConfig() {
        TNBuilderConfig tnBuilderConfig = new TNBuilderConfig();
        return tnBuilderConfig;
    }

    @Override
    public String toString() {
        try {
            ObjectMapper objectMapper = new ObjectMapper();
            objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
            return objectMapper.writeValueAsString(this);
        } catch (JsonProcessingException e) {
            e.printStackTrace();
            return "Failure";
        }
    }

    public void fillPath(String parent) {
        if (bikeRentalFile == null) {
            return;
        }
        bikeRentalFile = new File(parent, bikeRentalFile).getAbsolutePath();
    }
}
