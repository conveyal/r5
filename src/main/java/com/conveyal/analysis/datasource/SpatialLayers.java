package com.conveyal.analysis.datasource;

import com.conveyal.analysis.AnalysisServerException;

import com.conveyal.file.FileStorageFormat;
import com.google.common.collect.Sets;
import org.apache.commons.fileupload.FileItem;
import org.apache.commons.io.FilenameUtils;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Utility class with common static methods for validating and processing uploaded spatial data files.
 */
public abstract class SpatialLayers {

    /**
     * FIXME Originally used for OpportunityDataset upload, moved to SpatialLayers but should be named DataSource
     * Detect from a batch of uploaded files whether the user has uploaded a Shapefile, a CSV, or one or more binary
     * grids. In the process we validate the list of uploaded files, making sure certain preconditions are met.
     * Some kinds of uploads must contain multiple files (.shp) or can contain multiple files (.grid) while others
     * must have only a single file (.csv). Scan the list of uploaded files to ensure it makes sense before acting.
     * Note that this does not validate the contents of the files semantically, just the high-level characteristics of
     * the set of files.
     * @throws AnalysisServerException if the type of the upload can't be detected or preconditions are violated.
     * @return the expected type of the uploaded file or files, never null.
     */
    public static FileStorageFormat detectUploadFormatAndValidate (List<FileItem> fileItems) {
        if (fileItems == null || fileItems.isEmpty()) {
            throw AnalysisServerException.fileUpload("You must include some files to create an opportunity dataset.");
        }

        Set<String> fileExtensions = extractFileExtensions(fileItems);

        if (fileExtensions.contains("ZIP")) {
            throw AnalysisServerException.fileUpload("Upload of spatial .zip files not yet supported");
            // TODO unzip
            // detectUploadFormatAndValidate(unzipped)
        }

        // There was at least one file with an extension, the set must now contain at least one extension.
        if (fileExtensions.isEmpty()) {
            throw AnalysisServerException.fileUpload("No file extensions seen, cannot detect upload type.");
        }

        FileStorageFormat uploadFormat = null;

        // Check that if upload contains any of the Shapefile sidecar files, it contains all of the required ones.
        final Set<String> shapefileExtensions = Sets.newHashSet("SHP", "DBF", "PRJ");
        if ( ! Sets.intersection(fileExtensions, shapefileExtensions).isEmpty()) {
            if (fileExtensions.containsAll(shapefileExtensions)) {
                uploadFormat = FileStorageFormat.SHP;
                verifyBaseNamesSame(fileItems);
                // TODO check that any additional file is SHX, and that there are no more than 4 files.
            } else {
                final String message = "You must multi-select at least SHP, DBF, and PRJ files for shapefile upload.";
                throw AnalysisServerException.fileUpload(message);
            }
        }

        // Even if we've already detected a shapefile, run the other tests to check for a bad mixture of file types.
        // TODO factor the size == 1 check out of all cases
        if (fileExtensions.contains("GRID")) {
            if (fileExtensions.size() == 1) {
                uploadFormat = FileStorageFormat.GRID;
            } else {
                String message = "When uploading grids you may upload multiple files, but they must all be grids.";
                throw AnalysisServerException.fileUpload(message);
            }
        } else if (fileExtensions.contains("CSV")) {
            if (fileItems.size() == 1) {
                uploadFormat = FileStorageFormat.CSV;
            } else {
                String message = "When uploading CSV you may only upload one file at a time.";
                throw AnalysisServerException.fileUpload(message);
            }
        } else if (fileExtensions.contains("GEOJSON") || fileExtensions.contains("JSON")) {
            uploadFormat = FileStorageFormat.GEOJSON;
        } else if (fileExtensions.contains("GPKG")) {
            uploadFormat = FileStorageFormat.GEOPACKAGE;
        } else if (fileExtensions.contains("TIFF") || fileExtensions.contains("TIF")) {
            uploadFormat = FileStorageFormat.TIFF;
        }

        if (uploadFormat == null) {
            throw AnalysisServerException.fileUpload("Could not detect format of uploaded spatial data.");
        }
        return uploadFormat;
    }

    private static Set<String> extractFileExtensions (List<FileItem> fileItems) {

        Set<String> fileExtensions = new HashSet<>();

        for (FileItem fileItem : fileItems) {
            String fileName = fileItem.getName();
            String extension = FilenameUtils.getExtension(fileName);
            if (extension.isEmpty()) {
                throw AnalysisServerException.fileUpload("Filename has no extension: " + fileName);
            }
            fileExtensions.add(extension.toUpperCase());
        }

        return fileExtensions;
    }

    private static void verifyBaseNamesSame (List<FileItem> fileItems) {
        String firstBaseName = null;
        for (FileItem fileItem : fileItems) {
            // Ignore .shp.xml files, which will fail the verifyBaseNamesSame check
            if ("xml".equalsIgnoreCase(FilenameUtils.getExtension(fileItem.getName()))) continue;
            String baseName = FilenameUtils.getBaseName(fileItem.getName());
            if (firstBaseName == null) {
                firstBaseName = baseName;
            }
            if (!firstBaseName.equals(baseName)) {
                String message = "In a shapefile upload, all files must have the same base name.";
                throw AnalysisServerException.fileUpload(message);
            }
        }
    }

}
