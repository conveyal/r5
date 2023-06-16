package com.conveyal.r5.analyst.cluster;

import com.conveyal.r5.analyst.fare.InRoutingFareCalculator;
import com.conveyal.r5.analyst.scenario.Modification;
import com.conveyal.r5.analyst.scenario.RasterCost;
import com.conveyal.r5.analyst.scenario.ShapefileLts;
import com.conveyal.r5.profile.StreetMode;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;

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

    /** ID of the OSM file, for use with OSMCache */
    public String osmId;

    /** IDs of the GTFS files, for use with GTFSCache */
    public List<String> gtfsIds;

    /** The fare calculator for analysis, if any. TODO this is not yet wired up to TransportNetwork.setFareCalculator. */
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

}
