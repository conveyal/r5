package com.conveyal.analysis.models;

import com.conveyal.file.FileStorageFormat;
import com.conveyal.file.FileStorageKey;
import com.fasterxml.jackson.annotation.JsonIgnore;
import org.bson.types.ObjectId;

public class FileInfo extends BaseModel {
    public String regionId = null;

    // What is the bucket or folder that this file is stored in?
    public String bucket = null;

    // The path to create a FileStorageKey
    public String path = null;

    // Has the file been uploaded and is ready to be used?
    public Boolean isReady = false;

    // Size (in bytes)
    public Integer size = 0;

    // Internal file format category. Corresponds to an extension and mime type.
    public FileStorageFormat format = null;

    // Get path
    @JsonIgnore
    public FileStorageKey getKey () {
        return new FileStorageKey(bucket, path);
    }

    /**
     * New objects will be stored using this path.
     * TODO clean path?
     * @param accessGroup
     * @param _id
     * @param filename
     * @return
     */
    public static String generatePath (String accessGroup, ObjectId _id, String filename) {
        String fileName = String.join("-", _id.toString(), filename);
        return String.join("/", accessGroup, fileName);
    }
}
