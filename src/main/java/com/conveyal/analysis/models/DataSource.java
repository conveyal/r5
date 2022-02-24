package com.conveyal.analysis.models;

import com.conveyal.analysis.UserPermissions;
import com.conveyal.file.FileCategory;
import com.conveyal.file.FileStorageFormat;
import com.conveyal.file.FileStorageKey;
import com.conveyal.r5.analyst.progress.WorkProduct;
import org.bson.codecs.pojo.annotations.BsonDiscriminator;
import org.locationtech.jts.geom.Geometry;
import org.opengis.feature.simple.SimpleFeature;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.conveyal.r5.analyst.progress.WorkProductType.DATA_SOURCE;
import static com.conveyal.r5.common.GeometryUtils.geometryFactory;

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

    /** The size of the uploaded file, including any sidecar files. */
    public int fileSizeBytes;

    public FileStorageFormat fileFormat;

    /**
     * The geographic bounds of this data set in WGS84 coordinates (independent of the original CRS of uploaded file).
     * This type uses (north, south, east, west), ideally for consistency we'd use (minLon, minLat, maxLon, maxLat).
     */
    public Bounds wgsBounds;

    /**
     * Problems encountered while loading.
     * TODO should this be a separate json file in storage? Should it be a Set to deduplicate?
     */
    public List<DataSourceValidationIssue> issues = new ArrayList<>();

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

    public FileStorageKey fileStorageKey () {
        return new FileStorageKey(FileCategory.DATASOURCES, _id.toString(), fileFormat.extension);
    }

    /**
     * Return a list of JTS Geometries in WGS84 longitude-first CRS. These will serve as a preview for display on a
     * map. The number of features should be less than 1000. The userData on the first geometry in the list will
     * determine the schema. It must be null or a Map<String, Object>. The user data on all subsequent features must
     * have the same fields and types. All geometries in the list must be of the same type.
     * The default implementation returns a single feature, which is the bounding box of the DataSource.
     * These may not be tight geographic bounds on the data set, because many CRS are not axis-aligned with WGS84.
     */
    public List<? extends Geometry> wgsPreview () {
        Geometry geometry = geometryFactory.toGeometry(wgsBounds.envelope());
        geometry.setUserData(Map.of(
            "name", this.name,
            "id", this._id.toString()
        ));
        return List.of(geometry);
    }

}
