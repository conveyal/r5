package com.conveyal.util;

import org.apache.commons.fileupload.FileItem;
import org.apache.commons.fileupload.FileItemFactory;
import org.apache.commons.fileupload.disk.DiskFileItemFactory;
import org.apache.commons.fileupload.servlet.ServletFileUpload;
import spark.Request;
import spark.ResponseTransformer;

import javax.servlet.http.HttpServletRequest;
import java.io.UnsupportedEncodingException;
import java.util.List;
import java.util.Map;

public abstract class HttpUtils {

    /**
     * A Cache-Control header cvalue for immutable data. 2,592,000 seconds is one month.
     * https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/Cache-Control
     */
    public static final String CACHE_CONTROL_IMMUTABLE = "public, max-age=2592000, immutable";
    public static final ResponseTransformer toJson = JsonUtils::objectToJsonString;
    public static final String USER_PERMISSIONS_ATTRIBUTE = "permissions";

    /**
     * Extract files from a Spark Request containing RFC 1867 multipart form-based file upload data.
     */
    public static Map<String, List<FileItem>> getRequestFiles (HttpServletRequest req) {
        // The Javadoc on this factory class doesn't say anything about thread safety. Looking at the source code it
        // all looks threadsafe. But also very lightweight to instantiate, so in this code run by multiple threads
        // we play it safe and always create a new factory.
        // Setting a size threshold of 0 causes all files to be written to disk, which allows processing them in a
        // uniform way in other threads, after the request handler has returned. This does however cause some very
        // small form fields to be written to disk files. Ideally we'd identify the smallest actual file we'll ever
        // handle and set the threshold a little higher. The downside is that if a tiny file is actually uploaded even
        // by accident, our code will not be able to get a file handle for it and fail. Some legitimate files like
        // Shapefile .prj sidecars can be really small.
        // If we always saved the FileItems via write() or read them with getInputStream() they would not all need to
        // be on disk.
        try {
            FileItemFactory fileItemFactory = new DiskFileItemFactory(0, null);
            ServletFileUpload sfu = new ServletFileUpload(fileItemFactory);
            return sfu.parseParameterMap(req);
        } catch (Exception e) {
            throw HttpServerRuntimeException.fileUpload(ExceptionUtils.stackTraceString(e));
        }
    }

    /**
     * Get the specified field from a map representing a multipart/form-data POST request, as a UTF-8 String.
     * FileItems represent any form item that was received within a multipart/form-data POST request, not just files.
     * This is a static utility method that should be reusable across different HttpControllers.
     */
    public static String getFormField(Map<String, List<FileItem>> formFields, String fieldName, boolean required) {
        try {
            List<FileItem> fileItems = formFields.get(fieldName);
            if (fileItems == null || fileItems.isEmpty()) {
                if (required) {
                    throw HttpServerRuntimeException.badRequest("Missing required field: " + fieldName);
                } else {
                    return null;
                }
            }
            String value = fileItems.get(0).getString("UTF-8");
            return value;
        } catch (UnsupportedEncodingException e) {
            throw HttpServerRuntimeException.badRequest(String.format("Multipart form field '%s' had unsupported encoding",
                    fieldName));
        }
    }

    /**
     * Deserializes an object of the given type from the body of the supplied Spark request.
     * We use the lenient mapper for two reasons: 1. We use the type JSON property to select the Java class to
     * deserialize into, but that field doesn't exist on the resulting Java classes; 2. R5 modificaton classes may
     * evolve, usually in a backward compatible way by adding new fields, and we want older modifications without those
     * fields to deserialize without errors.
     */
    public static <T> T objectFromRequestBody(spark.Request request, Class<T> classe) {
        try {
            return JsonUtils.lenientObjectMapper.readValue(request.bodyAsBytes(), classe);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    /**
     * From an HTTP request object, extract a strongly typed UserPermissions object containing the user's email and
     * access group. This should be used almost everywhere instead of String email and accessGroup variables. Use this
     * method to encapsulate all calls to req.attribute(String) because those calls are not typesafe (they cast an Object
     * to whatever type seems appropriate in the context, or is supplied by the "req.<T>attribute(String)" syntax).
     */
    public static UserPermissions userFromRequest(Request req) {
        return req.attribute(USER_PERMISSIONS_ATTRIBUTE);
    }
}
