package com.conveyal.r5.point_to_point;

import com.conveyal.r5.common.JsonUtilities;
import com.conveyal.r5.point_to_point.builder.TNBuilderConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.util.Arrays;

/**
 * This will represent point to point searche server.
 *
 * It can build point to point TransportNetwork and start a server with API for point to point searches
 *
 */
public class PointToPointRouterServer {
    private static final Logger LOG = LoggerFactory.getLogger(PointToPointRouterServer.class);

    private static final int DEFAULT_PORT = 8080;

    private static final String DEFAULT_BIND_ADDRESS = "0.0.0.0";

    public static final String BUILDER_CONFIG_FILENAME = "build-config.json";

    private static final String USAGE = "It expects --build [path to directory with GTFS and PBF files] to build the graphs\nor --graphs [path to directory with graph] to start the server with provided graph";

    public static void main(String[] commandArguments) {

        LOG.info("Arguments: {}", Arrays.toString(commandArguments));

        //TODO: should we use JCommander?
        if ("--build".equals(commandArguments[0])) {

            File dir = new File(commandArguments[1]);

            if (!dir.isDirectory() && dir.canRead()) {
                LOG.error("'{}' is not a readable directory.", dir);
            }

            TNBuilderConfig builderConfig = loadJson(new File(dir, BUILDER_CONFIG_FILENAME));
            System.out.println("Summarizing builder config: " + BUILDER_CONFIG_FILENAME);
            System.out.println(builderConfig);
        } else if ("--help".equals(commandArguments[0])
                || "-h".equals(commandArguments[0])
                || "--usage".equals(commandArguments[0])
                || "-u".equals(commandArguments[0])) {
            System.out.println(USAGE);
        } else {
            LOG.info("Unknown argument: {}", commandArguments[0]);
            System.out.println(USAGE);
        }

    }

    /**
     * Open and parse the JSON file at the given path into a Jackson JSON tree. Comments and unquoted keys are allowed.
     * Returns null if the file does not exist,
     * Returns null if the file contains syntax errors or cannot be parsed for some other reason.
     * <p>
     * We do not require any JSON config files to be present because that would get in the way of the simplest
     * rapid deployment workflow. Therefore we return an empty JSON node when the file is missing, causing us to fall
     * back on all the default values as if there was a JSON file present with no fields defined.
     */
    public static TNBuilderConfig loadJson(File file) {
        try (FileInputStream jsonStream = new FileInputStream(file)) {
            TNBuilderConfig config = JsonUtilities.objectMapper
                .readValue(jsonStream, TNBuilderConfig.class);
            LOG.info("Found and loaded JSON configuration file '{}'", file);
            return config;
        } catch (FileNotFoundException ex) {
            LOG.info("File '{}' is not present. Using default configuration.", file);
            return TNBuilderConfig.defaultConfig();
        } catch (Exception ex) {
            LOG.error("Error while parsing JSON config file '{}': {}", file, ex.getMessage());
            System.exit(42); // probably "should" be done with an exception
            return null;
        }
    }
}
