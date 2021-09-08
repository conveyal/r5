package com.conveyal.analysis.models;

import org.bson.codecs.pojo.annotations.BsonDiscriminator;

/**
 * Placeholder for representing uploaded OSM data.
 */
@BsonDiscriminator(key="type", value="osm")
public class OsmDataSource extends DataSource {

}
