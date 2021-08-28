package com.conveyal.analysis.datasource;

import com.conveyal.analysis.UserPermissions;
import com.conveyal.analysis.models.DataSource;
import com.conveyal.file.FileStorageFormat;
import com.conveyal.r5.analyst.progress.ProgressListener;
import org.apache.commons.fileupload.FileItem;
import org.bson.types.ObjectId;

import java.io.File;
import java.util.stream.Collectors;

import static com.conveyal.file.FileStorageFormat.GEOJSON;
import static com.conveyal.file.FileStorageFormat.SHP;
import static com.conveyal.file.FileStorageFormat.TIFF;

/**
 * Logic for loading and validating a specific kind of input file, yielding a specific subclass of DataSource.
 * This plugs into DataSourceUploadAction, which handles the general parts of processing any new DataSource.
 */
public abstract class DataSourceIngester {

    /**
     * An accessor method that gives the general purpose DataSourceUploadAction and DataSourceIngester code a view of
     * the DataSource being constructed. This allows to DataSourceUploadAction to set all the shared general properties
     * of a DataSource and insert it into the database, leaving the DataSourceIngester to handle only the details
     * specific to its input format and DataSource subclass. Concrete subclasses should ensure that this method can
     * return an object immediately after they're constructed.
     */
    protected abstract DataSource dataSource ();

    /**
     * This method is implemented on concrete subclasses to provide logic for interpreting a particular file type.
     * This is potentially the slowest part of DataSource creation so is called asynchronously (in a background task).
     * A single File is passed in here (rather than in the subclass constructors) because the file is moved into
     * storage before ingestion. Some supported formats (only shapefile for now) are made up of more than one file,
     * which must all be in the same directory. Moving them into storage ensures they're all in the same directory with
     * the same base name as required, and only one of their complete file names must be provided.
     */
    public abstract void ingest (File file, ProgressListener progressListener);

    /**
     * This method takes care of setting the fields common to all kinds of DataSource, with the specific concrete
     * DataSourceIngester taking care of the rest.
     * Our no-arg BaseModel constructors are used for deserialization so they don't create an _id or nonce ObjectId();
     */
    public void initializeDataSource (String name, String description, String regionId, UserPermissions userPermissions) {
        DataSource dataSource = dataSource();
        dataSource._id = new ObjectId();
        dataSource.nonce = new ObjectId();
        dataSource.name = name;
        dataSource.regionId = regionId;
        dataSource.createdBy = userPermissions.email;
        dataSource.updatedBy = userPermissions.email;
        dataSource.accessGroup = userPermissions.accessGroup;
        dataSource.description = description;
    }

    /**
     * Factory method to return an instance of the appropriate concrete subclass for the given file format.
     */
    public static DataSourceIngester forFormat (FileStorageFormat format) {
        if (format == SHP) {
            return new ShapefileDataSourceIngester();
        } else if (format == GEOJSON) {
            return new GeoJsonDataSourceIngester();
        } else if (format == TIFF) { // really this enum value should be GEOTIFF rather than just TIFF.
            return new GeoTiffDataSourceIngester();
        } else {
            return new GeoPackageDataSourceIngester();
        }

    }
}
