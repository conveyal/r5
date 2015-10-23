package com.conveyal.r5.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * Hold version information.
 */
public class MavenVersion {
    private static final Logger LOG = LoggerFactory.getLogger(MavenVersion.class);

    public static final String commit;
    public static final String describe;

    static {
        Properties p = new Properties();

        try {
            InputStream is = MavenVersion.class.getClassLoader().getResourceAsStream("git.properties");
            p.load(is);
            is.close();
        } catch (IOException e) {
            LOG.error("Error loading git commit information", e);
        }

        commit = p.getProperty("git.commit.id");
        describe = p.getProperty("git.commit.id.describe");
    }
}
