package com.conveyal.analysis.datasource;

import com.conveyal.analysis.UserPermissions;
import com.conveyal.analysis.models.DataSource;
import com.conveyal.analysis.persistence.AnalysisCollection;
import com.conveyal.file.FileStorage;
import com.conveyal.file.FileStorageFormat;
import com.conveyal.file.FileStorageKey;
import com.conveyal.r5.analyst.progress.ProgressListener;
import com.conveyal.r5.analyst.progress.TaskAction;
import org.apache.commons.fileupload.FileItem;
import org.apache.commons.fileupload.disk.DiskFileItem;
import org.apache.commons.io.FilenameUtils;
import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import static com.conveyal.analysis.controllers.OpportunityDatasetController.getFormField;
import static com.conveyal.analysis.datasource.SpatialLayers.detectUploadFormatAndValidate;
import static com.conveyal.file.FileCategory.DATASOURCES;
import static com.conveyal.file.FileStorageFormat.GEOJSON;
import static com.conveyal.file.FileStorageFormat.SHP;
import static com.conveyal.file.FileStorageFormat.TIFF;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

/**
 * Given a batch of uploaded files, put them into FileStorage, categorize and validate them, and record metadata as
 * some specific subclass of DataSource. This implements TaskAction so it can be run in the background without blocking
 * the HTTP request and handler thread.
 */
public class DataSourceUploadAction implements TaskAction {

    private static final Logger LOG = LoggerFactory.getLogger(DataSourceUploadAction.class);

    // The Components used by this background task, which were formerly captured by an anonymous closure.
    // Using named and well-defined classes for these background actions makes data flow and depdendencies clearer.
    private final FileStorage fileStorage;
    private final AnalysisCollection<DataSource> dataSourceCollection;

    /** The files provided in the HTTP post form. These will be moved into storage. */
    private final List<FileItem> fileItems;

    /**
     * This DataSourceIngester provides encapsulated loading and validation logic for a single format, by composition
     * rather than subclassing. Format ingestion does not require access to the fileStorage or the database collection.
     */
    private DataSourceIngester ingester;

    /**
     * The file to be ingested, after it has been moved into storage. For Shapefiles and other such "sidecar" formats,
     * this is the main file (.shp), with the same base name and in the same directory as all its sidecar files.
     */
    private File file;

    // This method is a stopgaps - it seems like this should be done differently.
    public String getDataSourceName () {
        return ingester.dataSource().name;
    }

    public DataSourceUploadAction (
            FileStorage fileStorage,
            AnalysisCollection<DataSource> dataSourceCollection,
            List<FileItem> fileItems,
            DataSourceIngester ingester
    ) {
        this.fileStorage = fileStorage;
        this.dataSourceCollection = dataSourceCollection;
        this.fileItems = fileItems;
        this.ingester = ingester;
    }

    @Override
    public final void action (ProgressListener progressListener) throws Exception {
        progressListener.setWorkProduct(ingester.dataSource().toWorkProduct());
        moveFilesIntoStorage(progressListener);
        ingester.ingest(file, progressListener);
        dataSourceCollection.insert(ingester.dataSource());
    }

    /**
     * Move all files uploaded in the HTTP post form into (cloud) FileStorage from their temp upload location.
     * Called asynchronously (in a background task) because when using cloud storage, this transfer could be slow.
     * We could do this after processing instead of before, but consider the shapefile case: we can't be completely
     * sure the source temp files are all in the same directory. Better to process them after moving into one directory.
     * We should also consider whether preprocessing like conversion of GTFS to MapDBs should happen at this upload
     * stage. If so, then this logic needs to change a bit.
     */
    private final void moveFilesIntoStorage (ProgressListener progressListener) {
        // Loop through uploaded files, registering the extensions and writing to storage (with filenames that
        // correspond to the source id)
        progressListener.beginTask("Moving files into storage...", 1);
        final String dataSourceId = ingester.dataSource()._id.toString();
        for (FileItem fileItem : fileItems) {
            DiskFileItem dfi = (DiskFileItem) fileItem;
            // TODO use canonical extensions from filetype enum
            // TODO upper case? should we be using lower case?
            String extension = FilenameUtils.getExtension(fileItem.getName()).toUpperCase(Locale.ROOT);
            FileStorageKey key = new FileStorageKey(DATASOURCES, dataSourceId, extension);
            fileStorage.moveIntoStorage(key, dfi.getStoreLocation());
            if (fileItems.size() == 1 || extension.equalsIgnoreCase(SHP.extension)) {
                file = fileStorage.getFile(key);
            }
        }
        checkNotNull(file);
        checkState(file.exists());
    }

    /**
     * Given the HTTP post form fields from our data source creation endpoint, return a DataSourceUploadAction
     * instance set up to process the uploaded data in the background. This will fail fast on data files that we can't
     * recognize or have obvious problems. Care should be taken that this method contains no slow actions.
     */
    public static DataSourceUploadAction forFormFields (
            FileStorage fileStorage,
            AnalysisCollection<DataSource> dataSourceCollection,
            Map<String, List<FileItem>> formFields,
            UserPermissions userPermissions
    ) {
        // Extract required parameters. Throws AnalysisServerException on failure, e.g. if a field is missing.
        final String sourceName = getFormField(formFields, "sourceName", true);
        final String regionId = getFormField(formFields, "regionId", true);
        final List<FileItem> fileItems = formFields.get("sourceFiles");

        FileStorageFormat format = detectUploadFormatAndValidate(fileItems);
        DataSourceIngester ingester = DataSourceIngester.forFormat(format);

        String description = "From uploaded files: " + fileItems.stream()
                .map(FileItem::getName).collect(Collectors.joining(", "));
        ingester.initializeDataSource(sourceName, description, regionId, userPermissions);
        DataSourceUploadAction dataSourceUploadAction =
                new DataSourceUploadAction(fileStorage, dataSourceCollection, fileItems, ingester);

        return dataSourceUploadAction;
    }

}
