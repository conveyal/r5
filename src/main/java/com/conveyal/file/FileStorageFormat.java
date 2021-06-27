package com.conveyal.file;

import org.bson.codecs.pojo.annotations.BsonIgnore;

public enum FileStorageFormat {
    FREEFORM("pointset", "application/octet-stream"),
    GRID("grid", "application/octet-stream"),
    POINTSET("pointset", "application/octet-stream"),
    PNG("png", "image/png"),
    TIFF("tiff", "image/tiff"),
    CSV("csv", "text/csv"),

    // These are not currently used but plan to be in the future. Exact types need to be determined
    // GTFS("zip", "application/zip"),
    // PBF("pbf", "application/octet-stream"),

    // SHP implies .dbf and .prj, and optionally .shx
    SHP("shp", "application/octet-stream"),

    GEOJSON("json", "application/json");

    @BsonIgnore
    public final String extension;
    @BsonIgnore
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
