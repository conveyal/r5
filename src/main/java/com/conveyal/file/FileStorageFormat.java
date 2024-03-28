package com.conveyal.file;

import java.util.Locale;

/**
 * An enumeration of all the file types we handle as uploads, derived internal data, or work products.
 * Really this should be a union of several enumerated types (upload/internal/product) but Java does not allow this.
 */
public enum FileStorageFormat {
    FREEFORM("pointset", "application/octet-stream"),
    GRID("grid", "application/octet-stream"),
    POINTSET("pointset", "application/octet-stream"), // Why is this "pointset" extension duplicated?
    PNG("png", "image/png"),
    GEOTIFF("tif", "image/tiff"),
    CSV("csv", "text/csv"),

    // SHP implies .dbf and .prj, and optionally .shx
    SHP("shp", "application/octet-stream"),

    // Some of these are not yet used. Any file type that can be downloaded through `FileStorage` is required here.
    // In our internal storage, we may want to force less ambiguous .gtfs.zip .osm.pbf and .geojson.
    GTFS("zip", "application/zip"),
    OSMPBF("pbf", "application/octet-stream"),
    // Also can be application/geo+json, see https://www.iana.org/assignments/media-types/application/geo+json
    GEOJSON("geojson", "application/json"),
    JSON("json", "application/json"),
    // See requirement 3 http://www.geopackage.org/spec130/#_file_extension_name
    GEOPACKAGE("gpkg", "application/geopackage+sqlite3");

    // These fields will not be serialized into Mongo.
    // The default codec to serialize Enums into BSON for Mongo uses String name() and valueOf(String).
    // TODO array of file extensions, with the first one used canonically in FileStorage and the others for detection.
    public final String extension;
    public final String mimeType;

    FileStorageFormat (String extension, String mimeType) {
        this.extension = extension;
        this.mimeType = mimeType;
    }

    public static FileStorageFormat fromFilename (String filename) {
        String extension = filename.substring(filename.lastIndexOf(".") + 1).toLowerCase(Locale.ROOT);
        for (FileStorageFormat format : FileStorageFormat.values()) {
            if (format.extension.equals(extension)) {
                return format;
            }
        }
        return null;
    }
}
