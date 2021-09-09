package com.conveyal.r5.analyst.cluster;

import com.conveyal.r5.analyst.fare.InRoutingFareCalculator;

import java.util.List;

/**
 * All inputs and options that describe how to build a particular transport network (except the serialization version).
 * Previously called BundleManifest. Originally transportation data bundles were a zip file of GTFS files and OSM files.
 * OSM data is no longer specific to a GTFS feed, at least within our UI. OSM is now associated with a whole project.
 * So "new-style" bundles are no longer zip files of data, they are just references to OSM and GTFS files on S3.
 * The fields in this class do not contain filenames but IDs that will be sanitized and have file extensions added
 * before being looked up as S3 objects.
 */
public class TransportNetworkConfig {

    /** ID of the OSM file, for use with OSMCache */
    public String osmId;

    /** IDs of the GTFS files, for use with GTFSCache */
    public List<String> gtfsIds;

    /** The fare calculator for analysis, if any. TODO this is not yet wired up to TransportNetwork.setFareCalculator. */
    public InRoutingFareCalculator analysisFareCalculator;

    /** ID of the DataSource containing the elevation raster, if any. */
    public String elevationDataSource;

    /** ID of the linear shapefile DataSource containing bicycle LTS to be matched to streets, if any. */
    public String ltsDataSource;

    /** The name of the numeric attribute within the ltsDataSource containing LTS values from 1-4. */
    public String ltsAttributeName;

}
