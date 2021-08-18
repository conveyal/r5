package com.conveyal.file;

import org.bson.codecs.pojo.annotations.BsonIgnore;

public enum FileStorageFormat {
    FREEFORM("pointset", "application/octet-stream"),
    GRID("grid", "application/octet-stream"),
    POINTSET("pointset", "application/octet-stream"),
    PNG("png", "image/png"),
    TIFF("tiff", "image/tiff"),
    CSV("csv", "text/csv"),

    // SHP implies .dbf and .prj, and optionally .shx
    SHP("shp", "application/octet-stream"),

    // These final ones are not yet used.
    // In our internal storage, we may want to force less ambiguous .gtfs.zip .osm.pbf and .geo.json.
    GTFS("zip", "application/zip"),
    OSMPBF("pbf", "application/octet-stream"),
    GEOJSON("json", "application/json");

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
