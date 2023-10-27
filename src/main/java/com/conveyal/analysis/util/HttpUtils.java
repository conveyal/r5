package com.conveyal.analysis.util;

import com.conveyal.analysis.AnalysisServerException;
import com.conveyal.analysis.datasource.DataSourceException;
import com.conveyal.file.FileUtils;
import com.conveyal.r5.util.ExceptionUtils;
import org.apache.commons.fileupload.FileItem;
import org.apache.commons.fileupload.servlet.ServletFileUpload;

import javax.servlet.http.HttpServletRequest;
import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public abstract class HttpUtils {

    /**
     * A Cache-Control header cvalue for immutable data. 2,592,000 seconds is one month.
     * https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/Cache-Control
     */
    public static final String CACHE_CONTROL_IMMUTABLE = "public, max-age=2592000, immutable";

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
            ServletFileUpload sfu = new ServletFileUpload();
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

    public static List<File> storeFileItemsAndUnzip(List<FileItem> fileItems) {
        return storeFileItemsAndUnzip(fileItems, FileUtils.createScratchDirectory());
    }

    /**
     * Convert `FileItem`s into `File`s and move them into the given directory. Automatically unzip files. Return the
     * list of new `File` handles.
     */
    public static List<File> storeFileItemsAndUnzip(List<FileItem> fileItems, File directory) {
        List<File> files = new ArrayList<>();
        for (FileItem fi : fileItems) {
            File file = storeFileItemInDirectory(fi, directory);
            String name = file.getName();
            if (name.toLowerCase().endsWith(".zip")) {
                files.addAll(FileUtils.unZipFileIntoDirectory(file, directory));
            } else {
                files.add(file);
            }
        }
        return files;
    }

    public static List<File> storeFileItems(List<FileItem> fileItems) {
        File directory = FileUtils.createScratchDirectory();
        List<File> files = new ArrayList<>();
        for (FileItem fileItem : fileItems) {
            files.add(storeFileItemInDirectory(fileItem, directory));
        }
        return files;
    }

    public static File storeFileItem(FileItem fileItem) {
        return storeFileItemInDirectory(fileItem, FileUtils.createScratchDirectory());
    }

    public static File storeFileItemInDirectory(FileItem fileItem, File directory) {
        try {
            File file = new File(directory, fileItem.getName());
            fileItem.getInputStream().transferTo(FileUtils.getOutputStream(file));
            return file;
        } catch (IOException e) {
            throw new DataSourceException(e.getMessage());
        }
    }
}
