package com.conveyal.analysis.models;

import com.conveyal.analysis.UserPermissions;
import com.conveyal.file.FileStorageFormat;
import org.bson.codecs.pojo.annotations.BsonDiscriminator;

// Do we get any advantages from a DataSource class hierarchy as opposed to a class with some fields left null?
// Handling different classes may not be worth additional effort unless we take advantage of polymorphic methods.
// We'll have Mongo collections returning the supertype, leaving any specialized fields inaccessible.
// Usefulness will depend on how different the different subtypes are, and we don't know that yet.
// The main action we take on DataSources is to process them into derived data. That can't easily be polymorphic.
// Perhaps we should just have a DataSourceType enum with corresponding field, but we're then ignoring Java types.

/**
 * This represents a file which was uploaded by the user and validated by the backend. Instances are persisted to Mongo.
 * DataSources can be processed into derived products like aggregation areas, destination grids, and transport networks.
 * Subtypes exist to allow additional fields on certain kinds of data sources. The attribute "type" of instances
 * serialized into Mongo is a "discriminator" which determines the corresponding Java class on deserialization.
 */
@BsonDiscriminator(key="type")
public abstract class DataSource extends BaseModel {

    public String regionId;

    /** Description editable by end users */
    public String description;

    /**
     * Internally we store all files with the same ID as their database entry, but we retain the file name to help the
     * user recognize files they uploaded. We could also just put that info in the description.
     */
    public String originalFileName;

    public FileStorageFormat fileFormat;

    // This type uses (north, south, east, west), ideally we'd use (minLon, minLat, maxLon, maxLat).
    public Bounds wgsBounds;

    public DataSource (UserPermissions user, String name) {
        super(user, name);
    }

    /** Zero-argument constructor required for Mongo automatic POJO deserialization. */
    public DataSource () { }

}
