package com.conveyal.analysis.util;

import com.conveyal.analysis.AnalysisServerException;
import com.conveyal.r5.util.ExceptionUtils;
import org.apache.commons.fileupload.FileItem;
import org.apache.commons.fileupload.FileItemFactory;
import org.apache.commons.fileupload.disk.DiskFileItemFactory;
import org.apache.commons.fileupload.servlet.ServletFileUpload;

import javax.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;

public abstract class HttpUtils {
    /** Extract files from a Spark Request containing RFC 1867 multipart form-based file upload data. */
    public static Map<String, List<FileItem>> getRequestFiles (HttpServletRequest req) {
        // The Javadoc on this factory class doesn't say anything about thread safety. Looking at the source code it
        // all looks threadsafe. But also very lightweight to instantiate, so in this code run by multiple threads
        // we play it safe and always create a new factory.
        // Setting a size threshold of 0 causes all files to be written to disk, which allows processing them in a
        // uniform way in other threads, after the request handler has returned.
        FileItemFactory fileItemFactory = new DiskFileItemFactory(0, null);
        ServletFileUpload sfu = new ServletFileUpload(fileItemFactory);
        try {
            return sfu.parseParameterMap(req);
        } catch (Exception e) {
            throw AnalysisServerException.badRequest(ExceptionUtils.asString(e));
        }
    }
}
