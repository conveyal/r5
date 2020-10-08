package com.conveyal.analysis.models;

import com.conveyal.file.FileStorageFormat;
import com.conveyal.file.FileStorageKey;
import com.conveyal.r5.analyst.WebMercatorExtents;
import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * A model object for storing metadata about opportunity datasets in Mongo, for sharing it with the frontend.
 * The actual contents of the opportunity datasets are persisted to files on S3 and/or in a directory of the local
 * filesystem. They are in subclasses of PointSet, specifically Grid and FreeformPointSet. Note that these are no
 * longer strictly opportunity datasets - they may be sets of points with no attached opportunity densities.
 */
public class OpportunityDataset extends Model {

    /** For now all web Mercator grids are zoom level 9. Level 10 is probably ideal but will quadruple calculation.
     * TODO make adjustable
     * */
    public static final int ZOOM = 9;

    /** The human-readable name of the data source from which this came, provided by the user who uploaded it. */
    public String sourceName;

    /** The unique id for the data source (CSV file, Shapefile etc.) from which this dataset was derived. */
    public String sourceId;

    /** Bucket name on S3 where the opportunity data itself is persisted. */
    public String bucketName;

    /**
     * Bounds in web Mercator pixels.  Note that no zoom level is specified here, it's fixed to a constant 9.
     */
    public int north;
    public int west;
    public int width;
    public int height;

    /**
     * Total number of opportunities in the dataset, i.e. the sum of all opportunity counts at all points / grid cells.
     * It appears the UI doesn't use this now, but it could. We might want to remove it.
     */
    public double totalOpportunities;

    /** Number of points (or grid cells) in the pointset. */
    public int totalPoints;

    /**
     * A PointSet can be either a regular grid or freeform arbitrary points.
     * The field is initialized to GRID to handle the many grid pointset files that were created before this format
     * field was introduced.
     * TODO we should also do a database migration and set every missing format field to GRID.
     */
    public FileStorageFormat format = FileStorageFormat.GRID;

    /**
     * Part of the storage location for old opportunity datasets on S3. Note that this is called key, but it wasn't
     * actually the S3 key. The actual S3 key was derived from a method called getKey(), but this led to
     * unintentional side-effects of serializing the key after we deprecated it. See discussion in #53.
     *
     * Opportunity datasets are now stored on S3 using [region]/[_id].[format]. Previously, this field was the name for
     * an opportunity dataset (concatenated with a UUID); some old opportunity datasets are still saved on S3 using
     * using [region]/[key].[format], so we're keeping this field for backward compatibility.
     */
    @Deprecated
    public String key;

    /**
     * The key on S3 (or other object storage) where a persisted representation of a grid (e.g. .grid or .geotiff)
     * may be located.
     * This will use the deprecated key field (a descriptive string plus a UUID) if it's set when deserialized from
     * Mongo, otherwise we use the _id. See discussion in #53.
     */
    private String storageLocation(String extension) {
        return String.format("%s/%s.%s",
                this.regionId,
                this.key == null ? this._id : this.key,
                extension.toLowerCase()
        );
    }

    /**
     * The key on S3 (or other object storage) where the persisted grid or freeform pointset is located.
     */
    public String storageLocation() {
        return storageLocation(this.format.extension);
    }

    @JsonIgnore
    public FileStorageKey getStorageKey () {
        String path = storageLocation(this.format.extension);
        return new FileStorageKey(this.bucketName, path);
    }

    @JsonIgnore
    public FileStorageKey getStorageKey (FileStorageFormat fileFormat) {
        return new FileStorageKey(this.bucketName, storageLocation(fileFormat.extension));
    }

    @JsonIgnore
    public WebMercatorExtents getWebMercatorExtents () {
        return new WebMercatorExtents(west, north, width, height, ZOOM);
    }

    /** Analysis region this dataset was uploaded in. */
    public String regionId;
}
