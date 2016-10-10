package com.conveyal.r5.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * This collects the Maven artifact version information from a properties file written during the Maven build.
 * We use this to ensure that workers are running the exact version of the code that we want to keep results consistent.
 * Note that building the code in an IDE may not cause this to be updated. You'll need to do a command line build.
 */
public class R5Version {

    private static final Logger LOG = LoggerFactory.getLogger(R5Version.class);

    public static final String version;
    public static final String finalName;
    public static final String commit;
    public static final String describe;

    static {
        Properties p = new Properties();

        try {
            InputStream is = R5Version.class.getClassLoader().getResourceAsStream("version.r5.properties");
            p.load(is);
            is.close();
        } catch (IOException | NullPointerException e) {
            LOG.error("Error loading version and commit information", e);
        }
        version = getPropertyWithFallback(p, "version", "UNKNOWN");
        finalName = getPropertyWithFallback(p, "finalName", "UNKNOWN");
        commit = getPropertyWithFallback(p, "commit", "UNKNOWN");
        describe = getPropertyWithFallback(p, "describe", "UNKNOWN");
    }

    /**
     * Return the value for the given key in the supplied Properties.
     * If the key is not present or if the value appears to be an uninterpolated Maven variable, return the supplied
     * fallback value. See isse #190.
     */
    public static String getPropertyWithFallback (Properties properties, String key, String fallback) {
        String result = properties.getProperty(key);
        if (result == null) return fallback;
        if (result.startsWith("${")) return fallback;
        return result;
    }

}
