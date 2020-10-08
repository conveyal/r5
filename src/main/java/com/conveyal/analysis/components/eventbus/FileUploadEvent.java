package com.conveyal.analysis.components.eventbus;

/**
 * Created by abyrd on 2020-06-12
 */
public class FileUploadEvent extends Event {

    enum Status {
        BEGIN, PROCESSING, COMPLETE, ERRORED
    }

    enum FileType {
        GTFS, OSM, SHAPE
    }

    public final String fileId;

    public final FileType fileType;

    public final Status status;

    public FileUploadEvent (String fileId, FileType fileType, Status status) {
        this.fileId = fileId;
        this.fileType = fileType;
        this.status = status;
    }

}
