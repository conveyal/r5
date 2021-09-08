package com.conveyal.analysis.models;

import org.bson.codecs.pojo.annotations.BsonDiscriminator;

/**
 * Placeholder for representing uploaded GTFS data.
 */
@BsonDiscriminator(key="type", value="gtfs")
public class GtfsDataSource extends DataSource {

}
