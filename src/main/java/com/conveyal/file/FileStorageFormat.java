package com.conveyal.file;

import org.bson.codecs.pojo.annotations.BsonIgnore;

/**
 * An enumeration of all the file types we handle as uploads, derived internal data, or work products.
 * Really this should be a union of several enumerated types (upload/internal/product) but Java does not allow this.
 */
public enum FileStorageFormat {
    FREEFORM("pointset", "application/octet-stream"),
    GRID("grid", "application/octet-stream"),
    POINTSET("pointset", "application/octet-stream"), // Why is this "pointset" extension duplicated?
    PNG("png", "image/png"),
    TIFF("tiff", "image/tiff"),
    CSV("csv", "text/csv"),

    // SHP implies .dbf and .prj, and optionally .shx
    SHP("shp", "application/octet-stream"),

    // These final ones are not yet used.
    // In our internal storage, we may want to force less ambiguous .gtfs.zip .osm.pbf and .geo.json.
    GTFS("zip", "application/zip"),
    OSMPBF("pbf", "application/octet-stream"),
    // Also can be application/geo+json, see https://www.iana.org/assignments/media-types/application/geo+json
    GEOJSON("json", "application/json"),
    // See requirement 3 http://www.geopackage.org/spec130/#_file_extension_name
    GEOPACKAGE("gpkg", "application/geopackage+sqlite3");

    // These should not be serialized into Mongo. Default Enum codec uses String name() and valueOf(String).
    // TODO clarify whether the extension is used for backend storage, or for detecting type up uploaded files.
    public final String extension;
    public final String mimeType;

    FileStorageFormat (String extension, String mimeType) {
        this.extension = extension;
        this.mimeType = mimeType;
    }

    public static FileStorageFormat fromFilename (String filename) {
        String extension = filename.substring(filename.lastIndexOf(".") + 1);
        return FileStorageFormat.valueOf(extension.toUpperCase());
    }
}
