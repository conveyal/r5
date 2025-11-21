package com.conveyal.r5.analyst.cluster;

import com.conveyal.r5.analyst.fare.InRoutingFareCalculator;
import com.conveyal.r5.analyst.scenario.Modification;
import com.conveyal.r5.profile.StreetMode;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Set;

/**
 * All inputs and options that describe how to build a particular transport network (except the serialization version).
 * Previously called BundleManifest. Originally transportation data bundles were a zip file of GTFS files and OSM files.
 * OSM data is no longer specific to a GTFS feed, at least within our UI. OSM is now associated with a whole project.
 * So "new-style" bundles are no longer zip files of data, they are just references to OSM and GTFS files on S3.
 * The fields in this class do not contain filenames but IDs that will be sanitized and have file extensions added
 * before being looked up as S3 objects.
 *
 * Workers will try to deserialize this with a strict object mapper that doesn't tolerate unrecognized fields.
 * This is fine for TransportNetworkConfigs containing new features not supported by those old workers, where it's
 * reasonable to fail fast. However, on older workers (v6.6 or older) the message may be something cryptic about not
 * being able to read bundle manifest JSON, without mentioning the extra fields. Because of this fail-fast behavior,
 * we have to be sure not to serialize null values for unused newer fields, which would confuse older workers.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TransportNetworkConfig {

    /** ID of the OSM file. Used as a key for fetching from OSMCache. */
    public String osmId;

    /** IDs of the GTFS files. Used as keys for fetching from GTFSCache. */
    public List<String> gtfsIds;

    /**
     * The fare calculator for analysis, if any.
     * TODO this is not yet wired up to TransportNetwork.setFareCalculator.
     */
    public InRoutingFareCalculator analysisFareCalculator;

    /** A list of _R5_ modifications to apply during network build. May be null. */
    public List<Modification> modifications;

    /**
     * Additional modes other than walk for which to pre-build large data structures (grid linkage and cost tables).
     * When building a network, by default we build distance tables from transit stops to street vertices, to which we
     * connect a grid covering the entire street network at the default zoom level. By default we do this only for the
     * walk mode. Pre-building and serializing equivalent data structures for other modes allows workers to start up
     * much faster in regional analyses. The work need only be done once when the first single-point worker to builds
     * the network. Otherwise, hundreds of workers will each have to build these tables every time they start up.
     * Some scenarios, such as those that affect the street layer, may still be slower to apply for modes listed here
     * because some intermediate data (stop-to-vertex tables) are only retained for the walk mode. If this proves to be
     * a problem it is a candidate for future optimization.
     */
    public Set<StreetMode> buildGridsForModes;

    /**
     * Specifies which "labeler" to use when setting traversal mode permissions from OSM tags. For now, only
     * implemented with "sidewalk" to use the SidewalkTraversalPermissionLayer. This should eventually be cleaned up
     * (specifying different labelers, using enums).
     */
    public String traversalPermissionLabeler;

    /**
     * Whether to save detailed trip shapes from GTFS (e.g., for Conveyal Taui sites or the Network Viewer).
     * If false, straight line segments between stops will be used in visualizations.
     */
    public boolean saveShapes;

    /**
     * How to handle stop-to-stop transfers in GTFS transfers.txt, and how to combine them with OSM-derived transfers.
     * PER_STOP_PAIR is the default, and should only improve on the past behavior corresponding to OSM_ONLY.
     * There may be edge cases, such as a complex with subway platforms separated by long times specified in GTFS,
     * but no times specified for transfers between those platforms and nearby bus stops. In these cases, path results
     * may be strange or incorrect, but the quality of travel times would be no worse than the current OSM_ONLY option.
     *
     * Currently we only handle stop-to-stop transfers in GTFS transfers.txt. Other more specific transfers types like
     * route-to-route and trip-to-trip are not compatible with our current routing approach, so will be ignored with
     * a warning. We also only import transfer type 2 ("minimum amount of time between arrival and departure to ensure
     * a connection"). For any option except OSM_ONLY, these GTFS transfers override and replace any OSM street distance
     * calculations. Although the GTFS spec text says "minimum amount of time", implying that other information like OSM
     * routing could make times longer, we believe the proper interpretation is that transfers.txt provides a typical
     * safe amount of time needed to walk between the two stops, which would only be made worse by comparing with OSM.
     *
     * FIXME: Strangely, BOARD_SLACK_SECONDS appears to only be used in classes for displaying paths, not for routing.
     *
     * TODO: We currently apply a hard lower limit of 60 seconds between alighting and boarding.
     *   Should this be configurable or interact with the transfer entries?
     *
     */
    public GtfsTransferConfig gtfsTransfers;

    enum GtfsTransferConfig {
        OSM_ONLY,  // Find all transfers by searching through the OSM street network
        GTFS_ONLY, // Load transfers only from transfers.txt, do not use the street network
        PER_STOP_PAIR, // Find transfers via streets for stop pairs not connected by GTFS transfers
        PER_STOP, // Find transfers via streets only from and to stops not referenced by GTFS transfers
        PER_FEED // GTFS_ONLY for feeds with transfers.txt, OSM_ONLY for those without
    }
}
