package com.conveyal.analysis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileReader;
import java.io.Reader;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

/**
 * Shared functionality for classes that load properties containing configuration information and expose these options
 * via the Config interfaces of Components and HttpControllers. Subclasses are defined for Worker and Backend config.
 *
 * Some validation may be performed here, but any interpretation or conditional logic should be provided in Components
 * themselves, or possibly in alternate Components implementations.
 *
 * Example backend config files are shipped in the repo and we always supply a machine-generated config to the workers,
 * so it's easy to see an exhaustive list of all parameters. All configuration parameters are therefore required to
 * avoid any confusion due to merging layers of defaults.
 */
public class ConfigBase {

    private static final Logger LOG = LoggerFactory.getLogger(ConfigBase.class);

    public static final String CONVEYAL_PROPERTY_PREFIX = "conveyal-";

    // All access to these should be through the *Prop methods.
    private final Properties properties;

    protected final Set<String> keysWithErrors = new HashSet<>();

    /**
     * Prepare to load config from the given properties, overriding from environment variables and system properties.
     * In the latter two sources, the keys may be in upper or lower case and use dashes, underscores, or dots as
     * separators. System properties can be set on the JVM command line with -D options. The usual config file keys
     * must be prefixed with "conveyal", e.g. CONVEYAL_HEAVY_THREADS=5 or java -Dconveyal.heavy.threads=5.
     * Precedence of configuration sources is: system properties > environment variables > config file.
     */
    protected ConfigBase (Properties properties) {
        this.properties = properties;
        // Overwrite properties from config file with environment variables and system properties.
        // This could also be done with the Properties constructor that specifies defaults, but by manually
        // overwriting items we are able to log these potentially confusing changes to configuration.
        setPropertiesFromMap(System.getenv(), "environment variable");
        setPropertiesFromMap(System.getProperties(), "system properties");
    }

    /** Static convenience method to uniformly load files into properties and catch errors. */
    protected static Properties propsFromFile (String filename) {
        try (Reader propsReader = new FileReader(filename)) {
            Properties properties = new Properties();
            properties.load(propsReader);
            return properties;
        } catch (Exception e) {
            throw new RuntimeException("Could not load configuration properties.", e);
        }
    }

    // Always use the following *Prop methods to read properties. This will catch and log missing keys or parse
    // exceptions, allowing config loading to continue and reporting as many problems as possible at once.

    // Catches and records missing values,
    // so methods that wrap this and parse into non-String types can just ignore null values.
    protected String strProp (String key) {
        String value = properties.getProperty(key);
        if (value == null) {
            LOG.error("Missing configuration option {}", key);
            keysWithErrors.add(key);
        }
        return value;
    }

    protected int intProp (String key) {
        String val = strProp(key);
        if (val != null) {
            try {
                return Integer.parseInt(val);
            } catch (NumberFormatException nfe) {
                LOG.error("Value of configuration option '{}' could not be parsed as an integer: {}", key, val);
                keysWithErrors.add(key);
            }
        }
        return 0;
    }

    protected boolean boolProp (String key) {
        String val = strProp(key);
        if (val != null) {
            // Boolean.parseBoolean will return false for any string other than "true".
            // We want to be more strict.
            if ("true".equalsIgnoreCase(val) || "yes".equalsIgnoreCase(val)) {
                return true;
            } else if ("false".equalsIgnoreCase(val) || "no".equalsIgnoreCase(val)) {
                return false;
            } else {
                LOG.error("Value of configuration option '{}' could not be parsed as an integer: {}", key, val);
                keysWithErrors.add(key);
            }
        }
        return false;
    }

    /** Call this after reading all properties to enforce the presence of all configuration options. */
    protected void exitIfErrors () {
        if (!keysWithErrors.isEmpty()) {
            LOG.error("You must provide these configuration properties: {}", String.join(", ", keysWithErrors));
            System.exit(1);
        }
    }

    /**
     * Overwrite configuration options supplied in the config file with environment variables and system properties
     * (e.g. supplied on the JVM command line). Case and separators are normalized to conform to both properties and
     * environment variable conventions. Properties are Object-Object Maps so key and value are cast to String.
     */
    private void setPropertiesFromMap (Map<?, ?> map, String sourceDescription) {
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            // Normalize to String type, all lower case, all dash separators.
            String key = ((String)entry.getKey()).toLowerCase().replaceAll("[\\._-]", "-");
            String value = ((String)entry.getValue());
            if (key.startsWith(CONVEYAL_PROPERTY_PREFIX)) {
                // Strip off conveyal prefix to get the key that would be used in our config file.
                key = key.substring(CONVEYAL_PROPERTY_PREFIX.length());
                String existingKey = properties.getProperty(key);
                if (existingKey != null) {
                    LOG.info("Overwriting existing config key {} to '{}' from {}.", key, value, sourceDescription);
                } else {
                    LOG.info("Setting configuration key {} to '{}' from {}.", key, value, sourceDescription);
                }
                properties.setProperty(key, value);
            }
        }
    }

}
