package com.conveyal.analysis.models;

import com.conveyal.analysis.AnalysisServerException;
import com.conveyal.analysis.UserPermissions;
import com.conveyal.analysis.datasource.SpatialAttribute;
import com.conveyal.file.FileStorageFormat;
import com.conveyal.file.FileStorageKey;
import com.conveyal.r5.util.ShapefileReader;
import org.apache.commons.fileupload.FileItem;
import org.apache.commons.io.FilenameUtils;
import org.bson.codecs.pojo.annotations.BsonDiscriminator;
import org.locationtech.jts.geom.Envelope;
import org.opengis.referencing.FactoryException;
import org.opengis.referencing.operation.TransformException;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.conveyal.file.FileCategory.DATASOURCES;
import static com.conveyal.r5.analyst.Grid.checkWgsEnvelopeSize;

/**
 * A SpatialDataSource is metadata about a user-uploaded file containing geospatial features (e.g. shapefile, GeoJSON,
 * or CSV containing point coordinates) that has been validated and is ready to be processed into specific Conveyal
 * formats (e.g. grids and other spatial layers).
 * The defining characteristic of a SpatialDataSource is that it contains a set of "features" which all share a schema:
 * they all have the same set of attributes (named and typed fields), and one of these attributes is a geometry of a
 * dataset-wide type (polygon, linestring etc.) in a coordinate system referencing geographic space.
 */
@BsonDiscriminator(key="type", value="spatial")
public class SpatialDataSource extends DataSource {

    /** The number of features in this SpatialDataSource. */
    public int featureCount;

    /** All features in this SpatialDataSource have an attached geometry of this type. */
    public ShapefileReader.GeometryType geometryType;

    /** Every feature has this set of Attributes - this is essentially a schema. */
    public List<SpatialAttribute> attributes;

    public SpatialDataSource (UserPermissions userPermissions, String name) {
        super(userPermissions, name);
    }

    /** Zero-argument constructor required for Mongo automatic POJO deserialization. */
    public SpatialDataSource () { }

    /**
     * Fluent methods to avoid constructors with lots of positional paramters.
     * Not really a builder pattern since it doesn't construct immutable objects,
     * but due to automatic POJO Mongo storage we can't have immutable objects anyway.
     * Unfortunately these can't be placed on the superclass because they won't return the concrete subclass.
     * Hence more absurd Java verbosity for things that should be built in language features like named parameters.
     */
    // Given these restrictions I think I'd rather go with classic factory methods here instead of builders.

    public FileStorageKey storageKey() {
        return new FileStorageKey(DATASOURCES, this._id.toString(), fileFormat.toString());
    }

}
