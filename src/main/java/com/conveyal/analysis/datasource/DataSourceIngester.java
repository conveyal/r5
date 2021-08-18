package com.conveyal.analysis.datasource;

import com.conveyal.analysis.AnalysisServerException;
import com.conveyal.analysis.UserPermissions;
import com.conveyal.analysis.controllers.DataSourceController;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;
import java.util.stream.Collectors;

import static com.conveyal.analysis.controllers.OpportunityDatasetController.getFormField;
import static com.conveyal.analysis.datasource.SpatialLayers.detectUploadFormatAndValidate;
import static com.conveyal.file.FileCategory.DATASOURCES;

/**
 * Given a batch of uploaded files, put them into FileStorage, categorize and validate them, and record metadata.
 * This implements TaskAction so it can be run in the background without blocking the HTTP request and handler thread.
 */
public abstract class DataSourceIngester <D extends DataSource> implements TaskAction {

    private static final Logger LOG = LoggerFactory.getLogger(DataSourceIngester.class);

    // Components used by this bacground task - formerly captured by an anonymous closure.
    // By explicitly representing these fields, dependencies and data flow are clearer.
    private final FileStorage fileStorage;
    private final AnalysisCollection<DataSource> dataSourceCollection;

    protected final List<FileItem> fileItems;

    /**
     * The concrete DataSource instance being filled in by this ingester instance.
     * It should be created immediately by the subclass constructors.
     */
    protected D dataSource;

    /** One File instance per source file, after being moved into storage - they should all have the same name. */
    // TODO Is this necessary? Can't we just process the uploaded files before moving them into storage?
    //      clarify this in the Javadoc of DataSourceIngester.
    //      I guess we can't be sure they are all in the same directory which could confuse the SHP reader.
    protected List<File> files;

    public String getDataSourceId () {
        return dataSource._id.toString();
    };

    public String getRegionId () {
        return dataSource.regionId;
    };

    public String getDataSourceName () {
        return dataSource.name;
    }

    public DataSourceIngester (
            FileStorage fileStorage,
            AnalysisCollection<DataSource> dataSourceCollection,
            List<FileItem> fileItems
    ) {
        this.fileStorage = fileStorage;
        this.dataSourceCollection = dataSourceCollection;
        this.fileItems = fileItems;
    }

    @Override
    public final void action (ProgressListener progressListener) throws Exception {
        // Call shared logic to move all files into cloud storage from temp upload location.
        moveFilesIntoStorage(progressListener);
        // Call ingestion logic specific to the detected file format.
        ingest(progressListener);
        dataSourceCollection.insert(dataSource);
    }

    /**
     * Implement on concrete subclasses to provide logic for interpreting a single file type.
     * This is potentially the slowest part so is called asynchronously (in a background task).
     */
    public abstract void ingest (ProgressListener progressListener);

    /**
     * Called asynchronously (in a background task) because when using cloud storage, this transfer could be slow.
     * FIXME should we do this after processing, and also move any other created files into storage?
     *       Or shouod we make it clear that ingestion never produces additional files (what about mapDBs?)
     */
    private final void moveFilesIntoStorage (ProgressListener progressListener) {
        // Loop through uploaded files, registering the extensions and writing to storage (with filenames that
        // correspond to the source id)
        progressListener.beginTask("Moving files into storage...", 1);
        files = new ArrayList<>(fileItems.size());
        for (FileItem fileItem : fileItems) {
            DiskFileItem dfi = (DiskFileItem) fileItem;
            // TODO use canonical extensions from filetype enum
            // TODO upper case? should we be using lower case?
            String extension = FilenameUtils.getExtension(fileItem.getName()).toUpperCase(Locale.ROOT);
            FileStorageKey key = new FileStorageKey(DATASOURCES, getDataSourceId(), extension);
            fileStorage.moveIntoStorage(key, dfi.getStoreLocation());
            files.add(fileStorage.getFile(key));
        }
    }

    /***
     * Given the HTTP post form fields from our data source creation endpoint, return a concrete DataSourceIngester
     * instance set up to process the uploaded data in the background. This also allows us to fail fast on data files
     * that we can't recognize or have obvious problems. Care should be taken that this method contains no slow actions.
     */
    public static DataSourceIngester forFormFields (
            FileStorage fileStorage,
            AnalysisCollection<DataSource> dataSourceCollection,
            Map<String, List<FileItem>> formFields,
            UserPermissions userPermissions
    ) {
        // Extract values of required fields. Throws AnalysisServerException on failure, e.g. if a field is missing.
        final String sourceName = getFormField(formFields, "sourceName", true);
        final String regionId = getFormField(formFields, "regionId", true);
        final List<FileItem> fileItems = formFields.get("sourceFiles");

        FileStorageFormat format = detectUploadFormatAndValidate(fileItems);
        DataSourceIngester dataSourceIngester;
        if (format == FileStorageFormat.SHP) {
            dataSourceIngester = new ShapefileDataSourceIngester(
                fileStorage, dataSourceCollection, fileItems, userPermissions
            );
        } else {
            throw new IllegalArgumentException("Ingestion logic not yet defined for format: " + format);
        }
        // Arrgh no-arg constructors are used for deserialization so they don't create an _id or nonce ObjectId();
        // There has to be a better way to get all this garbage into the subclasses.
        // I think we need to go with composition instead of subclassing - specific format ingester does not need
        // access to fileStorage or database collection.
        dataSourceIngester.dataSource.name = sourceName;
        dataSourceIngester.dataSource.regionId = regionId;
        String fileNames = fileItems.stream().map(FileItem::getName).collect(Collectors.joining(", "));
        dataSourceIngester.dataSource.description = "From uploaded files: " + fileNames;
        return dataSourceIngester;
    }

}
