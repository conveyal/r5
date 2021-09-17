package com.conveyal.analysis.datasource;

import com.conveyal.analysis.AnalysisServerException;

import com.conveyal.file.FileStorageFormat;
import com.google.common.collect.Sets;
import org.apache.commons.fileupload.FileItem;
import org.apache.commons.fileupload.disk.DiskFileItem;
import org.apache.commons.io.FilenameUtils;

import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static com.conveyal.r5.common.Util.isNullOrEmpty;
import static com.google.common.base.Preconditions.checkState;

/**
 * Utility class with common static methods for validating and processing uploaded spatial data files.
 */
public abstract class DataSourceUtil {

    /**
     * Detect the format of a batch of user-uploaded files. Once the intended file type has been established, we
     * validate the list of uploaded files, making sure certain preconditions are met. Some kinds of uploads must
     * contain multiple files (.shp) while most others must contain only a single file (.csv, .gpkg etc.).
     * Note that this does not perform structural or semantic validation of file contents, just the high-level
     * characteristics of the set of file names.
     * @throws DataSourceException if the type of the upload can't be detected or preconditions are violated.
     * @return the expected type of the uploaded file or files, never null.
     */
    public static FileStorageFormat detectUploadFormatAndValidate (List<FileItem> fileItems) {
        if (isNullOrEmpty(fileItems)) {
            throw new DataSourceException("You must select some files to upload.");
        }
        Set<String> fileExtensions = extractFileExtensions(fileItems);
        if (fileExtensions.isEmpty()) {
            throw new DataSourceException("No file extensions seen, cannot detect upload type.");
        }
        checkFileCharacteristics(fileItems);
        if (fileExtensions.contains("zip")) {
            throw new DataSourceException("Upload of spatial .zip files not yet supported");
            // TODO unzip and process unzipped files - will need to peek inside to detect GTFS uploads first.
            // detectUploadFormatAndValidate(unzipped)
        }
        // Check that if upload contains any of the Shapefile sidecar files, it contains all of the required ones.
        final Set<String> shapefileExtensions = Sets.newHashSet("shp", "dbf", "prj");
        if ( ! Sets.intersection(fileExtensions, shapefileExtensions).isEmpty()) {
            if (fileExtensions.containsAll(shapefileExtensions)) {
                verifyBaseNamesSame(fileItems);
                // TODO check that any additional file is .shx, and that there are no more than 4 files.
            } else {
                throw new DataSourceException("You must multi-select at least SHP, DBF, and PRJ files for shapefile upload.");
            }
            return FileStorageFormat.SHP;
        }
        // The upload was not a Shapefile. All other formats should contain one single file.
        if (fileExtensions.size() != 1) {
            throw new DataSourceException("For any format but Shapefile, upload only one file at a time.");
        }
        final String extension = fileExtensions.stream().findFirst().get();
        // TODO replace with iteration over FileStorageFormat.values() and their lists of extensions
        if (extension.equals("grid")) {
            return FileStorageFormat.GRID;
        } else if (extension.equals("csv")) {
            return FileStorageFormat.CSV;
        } else if (extension.equals("geojson") || extension.equals("json")) {
            return FileStorageFormat.GEOJSON;
        } else if (extension.equals("gpkg")) {
            return FileStorageFormat.GEOPACKAGE;
        } else if (extension.equals("tif") || extension.equals("tiff") || extension.equals("geotiff")) {
            return FileStorageFormat.GEOTIFF;
        }
        throw new DataSourceException("Could not detect format of uploaded spatial data.");
    }

    /**
     * Check that all FileItems supplied are stored in disk files (not memory), that they are all readable and all
     * have nonzero size.
     */
    private static void checkFileCharacteristics (List<FileItem> fileItems) {
        for (FileItem fileItem : fileItems) {
            checkState(fileItem instanceof DiskFileItem, "Uploaded file was not stored to disk.");
            File diskFile = ((DiskFileItem)fileItem).getStoreLocation();
            checkState(diskFile.exists(), "Uploaded file does not exist on filesystem as expected.");
            checkState(diskFile.canRead(), "Read permissions were not granted on uploaded file.");
            checkState(diskFile.length() > 0, "Uploaded file was empty (contained no data).");
        }
    }

    /**
     * Given a list of FileItems, return a set of all unique file extensions present, normalized to lower case.
     * Always returns a set instance which may be empty, but never null.
     */
    private static Set<String> extractFileExtensions (List<FileItem> fileItems) {
        Set<String> fileExtensions = new HashSet<>();
        for (FileItem fileItem : fileItems) {
            String fileName = fileItem.getName();
            String extension = FilenameUtils.getExtension(fileName);
            if (extension.isEmpty()) {
                new DataSourceException("Filename has no extension: " + fileName);
            }
            fileExtensions.add(extension.toLowerCase(Locale.ROOT));
        }
        return fileExtensions;
    }

    /** In uploads containing more than one file, all files are expected to have the same name before the extension. */
    private static void verifyBaseNamesSame (List<FileItem> fileItems) {
        String firstBaseName = null;
        for (FileItem fileItem : fileItems) {
            String baseName = FilenameUtils.getBaseName(fileItem.getName());
            if (firstBaseName == null) {
                firstBaseName = baseName;
            } else if (!firstBaseName.equals(baseName)) {
                throw new DataSourceException("In a shapefile upload, all files must have the same base name.");
            }
        }
    }

}
