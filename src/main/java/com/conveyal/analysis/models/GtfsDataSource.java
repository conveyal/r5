package com.conveyal.analysis.models;

import org.bson.codecs.pojo.annotations.BsonDiscriminator;

/**
 * Placeholder for representing uploaded GTFS data.
 *
 * We may want to merge SpatialDataSource up into DataSource and consider them all "spatial", and just hard-wire
 * methods to return DefaultGeographicCrs.WGS84 for GTFS and OSM DataSources.
 */
@BsonDiscriminator(key="type", value="gtfs")
public class GtfsDataSource extends DataSource {

}
