package com.conveyal.file;

public enum FileStorageFormat {
    FREEFORM("pointset", "application/octet-stream"),
    GRID("grid", "application/octet-stream"),
    POINTSET("pointset", "application/octet-stream"),
    PNG("png", "image/png"),
    TIFF("tiff", "image/tiff");

    // These are not currently used but plan to be in the future. Exact types need to be determined
    // CSV("csv", "text/csv"),
    // GTFS("zip", "application/zip"),
    // PBF("pbf", "application/octet-stream"),
    // SHP("shp", "application/octet-stream") // This type does not work as is, it should be a zip?

    public final String extension;
    public final String mimeType;

    FileStorageFormat(String extension, String mimeType) {
        this.extension = extension;
        this.mimeType = mimeType;
    }

    public static FileStorageFormat fromFilename (String filename) {
        String extension = filename.substring(filename.lastIndexOf(".") + 1);
        return FileStorageFormat.valueOf(extension.toUpperCase());
    }
}
