package com.conveyal.analysis.components.eventbus;

/**
 * Signals that a request has been processed over the HTTP API.
 * This no longer includes some database actions, as the UI is now capable of contacting the Mongo database directly.
 */
public class HttpApiEvent extends Event {

    public final String method;

    public final int statusCode;

    /** The URL path of the API endpoint. */
    public final String path;

    /**
     * Total time taken to process the API request.
     * Local database operations often take less than 1 msec, which gets truncated to zero.
     */
    public final long durationMsec;

    public HttpApiEvent (String method, int statusCode, String path, long durationMsec) {
        this.method = method;
        this.statusCode = statusCode;
        this.path = path;
        this.durationMsec = durationMsec;
    }

    @Override
    public String toString () {
        return String.format("[HTTP %s %s by %s of group %s, status code %d, duration %d msec]",
                method, path, user, accessGroup, statusCode, durationMsec);
    }
}
