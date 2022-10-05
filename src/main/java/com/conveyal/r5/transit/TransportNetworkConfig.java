package com.conveyal.r5.transit;

import com.conveyal.r5.fare.InRoutingFareCalculator;
import com.conveyal.r5.scenario.Modification;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

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

}
