package com.conveyal.r5.analyst.cluster;

import java.util.List;

/**
 * Describes the locations of files used to create a bundle.
 */
public class BundleManifest {
    /** ID of the OSM file, for use with OSMCache */
    public String osmId;

    /** IDs of the GTFS files, for use with GTFSCache */
    public List<String> gtfsIds;
}
