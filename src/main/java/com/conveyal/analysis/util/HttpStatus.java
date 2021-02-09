package com.conveyal.analysis.util;

/**
 * It's kind of absurd to have our own set of HTTP status code constants, but I can't find any in the Spark project.
 */
public class HttpStatus {

    public static final int OK_200 = 200;
    public static final int ACCEPTED_202 = 202;
    public static final int NO_CONTENT_204 = 204;
    public static final int BAD_REQUEST_400 = 400;
    public static final int SERVER_ERROR_500 = 500;
    public static final int BAD_GATEWAY_502 = 502;
    public static final int SERVICE_UNAVAILABLE = 000;
}
