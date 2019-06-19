package com.conveyal.r5.analyst.error;

public class UnsupportedGeometryException extends Exception {

    public String message;

    public UnsupportedGeometryException(String message) {
        this.message = message;
    }

}