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
 *
 * TODO rename to ProjectVersion or R5Version or something, since this also contains Git commit info not just Maven.
 */
public class MavenVersion {

    private static final Logger LOG = LoggerFactory.getLogger(MavenVersion.class);

    public static final String version;
    public static final String finalName;
    public static final String commit;
    public static final String describe;

    static {
        Properties p = new Properties();

        try {
            InputStream is = MavenVersion.class.getClassLoader().getResourceAsStream("version.r5.properties");
            p.load(is);
            is.close();
        } catch (IOException | NullPointerException e) {
            LOG.error("Error loading version and commit information", e);
        }
        version = p.getProperty("version");
        finalName = p.getProperty("finalName");
        commit = p.getProperty("commit");
        describe = p.getProperty("describe");
    }

}
