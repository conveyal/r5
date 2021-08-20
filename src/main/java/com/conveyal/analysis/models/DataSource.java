package com.conveyal.analysis.models;

import com.conveyal.analysis.UserPermissions;
import com.conveyal.file.FileStorageFormat;
import com.conveyal.r5.analyst.progress.WorkProduct;
import org.bson.codecs.pojo.annotations.BsonDiscriminator;

import java.util.List;

import static com.conveyal.r5.analyst.progress.WorkProductType.DATA_SOURCE;

/**
 * This represents a file which was uploaded by the user and validated by the backend. Instances are persisted to Mongo.
 * DataSources can be processed into derived products like aggregation areas, destination grids, and transport networks.
 * Subtypes exist to allow additional fields on certain kinds of data sources. The attribute "type" of instances
 * serialized into Mongo is a "discriminator" which determines the corresponding Java class on deserialization.
 *
 * Given the existence of descriminators in the Mongo driver, for now we're trying full Java typing with inheritance.
 * It is debatable whether we get any advantages from this DataSource class hierarchy as opposed to a single class with
 * some fields left null in certain cases (e.g. feature schema is null on OSM DataSources). Juggling different classes
 * may not be worth the trouble unless we get some utility out of polymorphic methods. For example, Mongo collections
 * will return the shared supertype, leaving any specialized fields inaccessible except via overridden methods.
 * Usefulness will depend on how different the different subtypes are, and we haven't really seen that yet.
 * The main action we take on DataSources is to process them into derived data. That can't easily use polymorphism.
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

    /** Problems encountered while loading. TODO should this be a separate json file in storage? */
    public List<DataSourceValidationIssue> issues;

    public DataSource (UserPermissions user, String name) {
        super(user, name);
    }

    /** Zero-argument constructor required for Mongo automatic POJO deserialization. */
    public DataSource () { }

    public WorkProduct toWorkProduct () {
        return new WorkProduct(DATA_SOURCE, _id.toString(), regionId);
    };

    public void addIssue (DataSourceValidationIssue.Level level, String message) {
        issues.add(new DataSourceValidationIssue(level, message));
    }

}
