package com.conveyal.analysis;

import com.conveyal.r5.util.ExceptionUtils;
import graphql.GraphQLError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class AnalysisServerException extends RuntimeException {
    private static final Logger LOG = LoggerFactory.getLogger(AnalysisServerException.class);

    public int httpCode;
    public TYPE type;
    public String message;

    public enum TYPE {
        BAD_REQUEST,
        BROKER,
        FILE_UPLOAD,
        FORBIDDEN,
        GRAPHQL,
        JSON_PARSING,
        NONCE,
        NOT_FOUND,
        UNAUTHORIZED,
        UNKNOWN;
    }

    public static AnalysisServerException badRequest(String message) {
        return new AnalysisServerException(TYPE.BAD_REQUEST, message, 400);
    }

    public static AnalysisServerException fileUpload(String message) {
        return new AnalysisServerException(TYPE.FILE_UPLOAD, message, 400);
    }

    public static AnalysisServerException forbidden(String message) {
        return new AnalysisServerException(TYPE.FORBIDDEN, message, 403);
    }

    public static AnalysisServerException graphQL(List<GraphQLError> errors) {
        return new AnalysisServerException(
                TYPE.GRAPHQL,
                errors
                    .stream()
                    .map(e -> e.getMessage())
                    .reduce("", (a, b) -> a + " " + b),
                400
        );
    }

    public static AnalysisServerException nonce() {
        return new AnalysisServerException(TYPE.NONCE, "The data you attempted to change is out of date and could not be " +
                "updated. This project may be open by another user or in another browser tab.", 400);
    }

    public static AnalysisServerException notFound(String message) {
        return new AnalysisServerException(TYPE.NOT_FOUND, message, 404);
    }

    public static AnalysisServerException unauthorized(String message) {
        return new AnalysisServerException(TYPE.UNAUTHORIZED, message, 401);
    }

    public static AnalysisServerException unknown(Exception e) {
        return new AnalysisServerException(TYPE.UNKNOWN, ExceptionUtils.asString(e), 400);
    }

    public static AnalysisServerException unknown(String message) {
        return new AnalysisServerException(TYPE.UNKNOWN, message, 400);
    }

    public AnalysisServerException(Exception e, String message) {
        this(message);
        LOG.error(ExceptionUtils.asString(e));
    }

    public AnalysisServerException(String message) {
        this(TYPE.UNKNOWN, message, 400);
    }

    public AnalysisServerException(AnalysisServerException.TYPE t, String m, int c) {
        httpCode = c;
        type = t;
        message = m;
    }

}
