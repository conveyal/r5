package com.conveyal.r5.transit;

/** Generic runtime exception for any problem encountered when building or loading a TransportNetwork. */
public class TransportNetworkException extends RuntimeException {

    public TransportNetworkException (String message) {
        super(message);
    }

    public TransportNetworkException (String message, Throwable cause) {
        super(message, cause);
    }

}
