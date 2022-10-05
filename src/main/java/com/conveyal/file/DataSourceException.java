package com.conveyal.file;

public class DataSourceException extends RuntimeException {

    public DataSourceException (String message) {
        super(message);
    }

    public DataSourceException (String message, Throwable cause) {
        super(message, cause);
    }

}
