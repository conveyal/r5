package com.conveyal.analysis;

import com.conveyal.r5.util.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AnalysisServerException extends RuntimeException {
    private static final Logger LOG = LoggerFactory.getLogger(AnalysisServerException.class);

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

    public static AnalysisServerException badRequest(String message) {
        return new AnalysisServerException(Type.BAD_REQUEST, message, 400);
    }

    public static AnalysisServerException fileUpload(String message) {
        return new AnalysisServerException(Type.FILE_UPLOAD, message, 400);
    }

    public static AnalysisServerException forbidden(String message) {
        return new AnalysisServerException(Type.FORBIDDEN, message, 403);
    }

    public static AnalysisServerException nonce() {
        return new AnalysisServerException(Type.NONCE, "The data you attempted to change is out of date and could not be " +
                "updated. This project may be open by another user or in another browser tab.", 400);
    }

    public static AnalysisServerException notFound(String message) {
        return new AnalysisServerException(Type.NOT_FOUND, message, 404);
    }

    // Note that there is a naming mistake in the HTTP codes. 401 "unauthorized" actually means "unauthenticated".
    // 403 "forbidden" is what is usually referred to as "unauthorized" in other contexts.
    public static AnalysisServerException unauthorized(String message) {
        return new AnalysisServerException(Type.UNAUTHORIZED, message, 401);
    }

    public static AnalysisServerException unknown(Exception e) {
        return new AnalysisServerException(Type.UNKNOWN, ExceptionUtils.stackTraceString(e), 400);
    }

    public static AnalysisServerException unknown(String message) {
        return new AnalysisServerException(Type.UNKNOWN, message, 400);
    }

    public AnalysisServerException(Exception e, String message) {
        this(message);
        LOG.error(ExceptionUtils.stackTraceString(e));
    }

    public AnalysisServerException(String message) {
        this(Type.UNKNOWN, message, 400);
    }

    public AnalysisServerException(Type t, String m, int c) {
        httpCode = c;
        type = t;
        message = m;
    }

    @Override
    public String getMessage() {
        return message;
    }
}
