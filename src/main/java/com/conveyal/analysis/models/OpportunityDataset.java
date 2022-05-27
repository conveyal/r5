package com.conveyal.analysis.models;

import com.conveyal.file.FileStorageFormat;
import com.conveyal.file.FileStorageKey;
import com.conveyal.r5.analyst.WebMercatorExtents;
import com.fasterxml.jackson.annotation.JsonIgnore;

import static com.conveyal.file.FileCategory.GRIDS;

/**
 * A model object for storing metadata about opportunity datasets in Mongo, for sharing it with the frontend.
 * The actual contents of the opportunity datasets are persisted to files on S3 and/or in a directory of the local
 * filesystem. They are in subclasses of PointSet, specifically Grid and FreeformPointSet. Note that these are no
 * longer strictly opportunity datasets - they may be sets of points with no attached opportunity densities.
 */
public class OpportunityDataset extends Model {

    /** The human-readable name of the data source from which this came, provided by the user who uploaded it. */
    public String sourceName;

    /** The unique id for the data source (CSV file, Shapefile etc.) from which this dataset was derived. */
    public String sourceId;

    /** The ID of the DataGroup that this OpportunityDataset belongs to (all created at once from a single source). */
    public String dataGroupId;

    /**
     * Bucket name on S3 where the opportunity data itself is persisted. Deprecated: as of April 2021, the FileStorage
     * system encapsulates how local or remote storage coordinates are derived from the FileCategory.
     */
    @Deprecated
    public String bucketName;

    /**
     * Bounds in web Mercator pixels.
     */
    public int north;
    public int west;
    public int width;
    public int height;

    /**
     * The zoom level this opportunity dataset was rasterized at.
     */
    public int zoom;

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
        return new FileStorageKey(GRIDS, path);
    }

    @JsonIgnore
    public FileStorageKey getStorageKey (FileStorageFormat fileFormat) {
        return new FileStorageKey(GRIDS, storageLocation(fileFormat.extension));
    }

    @JsonIgnore
    public void setWebMercatorExtents (WebMercatorExtents extents) {
        // These bounds are currently in web Mercator pixels, which are relevant to Grids but are not natural units
        // for FreeformPointSets. There are only unique minimal web Mercator bounds for FreeformPointSets if
        // the zoom level is fixed in OpportunityDataset.
        // Perhaps these metadata bounds should be WGS84 instead, it depends how the UI uses them.
        this.west = extents.west;
        this.north = extents.north;
        this.width = extents.width;
        this.height = extents.height;
        this.zoom = extents.zoom;
    }

    /** Analysis region this dataset was uploaded in. */
    public String regionId;
}
