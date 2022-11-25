package com.conveyal.analysis.models;

import com.conveyal.file.FileStorageFormat;
import org.bson.codecs.pojo.annotations.BsonDiscriminator;

/**
 * Placeholder for representing uploaded OSM data.
 */
@BsonDiscriminator(key="type", value="osm")
public class OsmDataSource extends DataSource {

    int nodes;
    int ways;
    int relations;

    public OsmDataSource() {
        this.fileFormat = FileStorageFormat.OSMPBF;
    }

}
