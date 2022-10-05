package com.conveyal.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class HttpServerRuntimeException extends RuntimeException {
    private static final Logger LOG = LoggerFactory.getLogger(HttpServerRuntimeException.class);

    public int httpCode;
    public Type type;
    public String message;

    public enum Type {
        BAD_REQUEST,
        BROKER,
        FILE_UPLOAD,
        FORBIDDEN,
        GRAPHQL,
        JSON_PARSING,
        NONCE,
        NOT_FOUND,
        RUNTIME,
        UNAUTHORIZED,
        UNKNOWN;
    }

    public static HttpServerRuntimeException badRequest(String message) {
        return new HttpServerRuntimeException(Type.BAD_REQUEST, message, 400);
    }

    public static HttpServerRuntimeException fileUpload(String message) {
        return new HttpServerRuntimeException(Type.FILE_UPLOAD, message, 400);
    }

    public static HttpServerRuntimeException forbidden(String message) {
        return new HttpServerRuntimeException(Type.FORBIDDEN, message, 403);
    }

    public static HttpServerRuntimeException nonce() {
        return new HttpServerRuntimeException(Type.NONCE, "The data you attempted to change is out of date and could not be " +
                "updated. This project may be open by another user or in another browser tab.", 400);
    }

    public static HttpServerRuntimeException notFound(String message) {
        return new HttpServerRuntimeException(Type.NOT_FOUND, message, 404);
    }

    // Note that there is a naming mistake in the HTTP codes. 401 "unauthorized" actually means "unauthenticated".
    // 403 "forbidden" is what is usually referred to as "unauthorized" in other contexts.
    public static HttpServerRuntimeException unauthorized(String message) {
        return new HttpServerRuntimeException(Type.UNAUTHORIZED, message, 401);
    }

    public static HttpServerRuntimeException unknown(Exception e) {
        return new HttpServerRuntimeException(Type.UNKNOWN, ExceptionUtils.stackTraceString(e), 400);
    }

    public static HttpServerRuntimeException unknown(String message) {
        return new HttpServerRuntimeException(Type.UNKNOWN, message, 400);
    }

    public HttpServerRuntimeException(Exception e, String message) {
        this(message);
        LOG.error(ExceptionUtils.stackTraceString(e));
    }

    public HttpServerRuntimeException(String message) {
        this(Type.UNKNOWN, message, 400);
    }

    public HttpServerRuntimeException(Type t, String m, int c) {
        httpCode = c;
        type = t;
        message = m;
    }

    @Override
    public String getMessage() {
        return message;
    }
}
