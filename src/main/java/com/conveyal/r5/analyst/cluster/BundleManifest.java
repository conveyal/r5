package com.conveyal.r5.analyst.cluster;

import java.util.List;

/**
 * Originally transportation data "bundles" were a zip file of GTFS files and OSM files.
 * OSM data is no longer specific to a GTFS feed, at least within our UI. OSM is now associated with a whole project.
 * So "new-style" bundles are no longer zip files of data, they are just references to OSM and GTFS files on S3.
 * The fields in this class do not contain filenames but IDs that will be sanitized and have file extensions added
 * before being looked up as S3 objects.
 */
public class BundleManifest {
    /** ID of the OSM file, for use with OSMCache */
    public String osmId;

    /** IDs of the GTFS files, for use with GTFSCache */
    public List<String> gtfsIds;
}
