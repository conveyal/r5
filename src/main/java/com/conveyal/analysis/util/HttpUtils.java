package com.conveyal.analysis.util;

import com.conveyal.analysis.AnalysisServerException;
import com.conveyal.r5.util.ExceptionUtils;
import org.apache.commons.fileupload.FileItem;
import org.apache.commons.fileupload.FileItemFactory;
import org.apache.commons.fileupload.disk.DiskFileItemFactory;
import org.apache.commons.fileupload.servlet.ServletFileUpload;

import javax.servlet.http.HttpServletRequest;
import java.io.UnsupportedEncodingException;
import java.util.List;
import java.util.Map;

public abstract class HttpUtils {

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
        try {
            FileItemFactory fileItemFactory = new DiskFileItemFactory(0, null);
            ServletFileUpload sfu = new ServletFileUpload(fileItemFactory);
            return sfu.parseParameterMap(req);
        } catch (Exception e) {
            throw AnalysisServerException.fileUpload(ExceptionUtils.stackTraceString(e));
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
                    throw AnalysisServerException.badRequest("Missing required field: " + fieldName);
                } else {
                    return null;
                }
            }
            String value = fileItems.get(0).getString("UTF-8");
            return value;
        } catch (UnsupportedEncodingException e) {
            throw AnalysisServerException.badRequest(String.format("Multipart form field '%s' had unsupported encoding",
                    fieldName));
        }
    }
}
