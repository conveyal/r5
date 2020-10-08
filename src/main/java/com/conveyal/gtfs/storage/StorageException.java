package com.conveyal.gtfs.storage;

import com.conveyal.gtfs.error.NewGTFSErrorType;

/**
 * Created by abyrd on 2017-03-25
 */
public class StorageException extends RuntimeException {

    public NewGTFSErrorType errorType = NewGTFSErrorType.OTHER;

    public String badValue = null;

    public StorageException(NewGTFSErrorType errorType, String badValue) {
        super(errorType.englishMessage);
        this.errorType = errorType;
        this.badValue = badValue;
    }

    public StorageException(Exception ex) {
        super(ex);
    }

    public StorageException (String message) {
        super(message);
    }

    public StorageException(String message, Exception ex) {
        super(message, ex);
    }

}
