package com.conveyal.analysis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * This collects the Maven and Git version information from a properties file written during the Maven build.
 * We use this to allow the backend to report the exact version of the code it is running via an API. This is useful
 * in automated deployment, testing, and debugging situations. Note that when building the code in an IDE, the version
 * information may not be supplied to this class. It may only be provided in a command line Maven build.
 */
public class BackendVersion {

    private static final Logger LOG = LoggerFactory.getLogger(BackendVersion.class);
    private static final String UNKNOWN = "UNKNOWN";

    public static final BackendVersion instance = new BackendVersion();

    private final Properties properties = new Properties();

    // Version number should be very similar to a git describe, with possible addition of .dirty
    public final String version;
    public final String commit;
    public final String branch;

    private BackendVersion () {
        try {
            InputStream is = BackendVersion.class.getClassLoader().getResourceAsStream("version.properties");
            properties.load(is);
            is.close();
        } catch (IOException | NullPointerException e) {
            LOG.error("Error loading version and commit information for Analysis Backend: {}", e.toString());
        }
        version = getPropertyOrUnknown("version");
        commit = getPropertyOrUnknown("commit");
        branch = getPropertyOrUnknown("branch");
    }

    /**
     * Return the value for the given key in the supplied Properties. If the key is not present or if the value appears
     * to be an uninterpolated Maven variable, return a fallback value. See R5 issue #190.
     */
    private String getPropertyOrUnknown(String key) {
        String result = properties.getProperty(key);
        if (result == null || result.startsWith("${")) {
            return UNKNOWN;
        }
        return result;
    }

}
