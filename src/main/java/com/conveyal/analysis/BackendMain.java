package com.conveyal.analysis;

import com.conveyal.analysis.components.Components;
import com.conveyal.analysis.components.LocalComponents;
import com.conveyal.analysis.grids.SeamlessCensusGridExtractor;
import com.conveyal.analysis.persistence.Persistence;
import com.conveyal.gtfs.api.ApiMain;
import com.conveyal.r5.analyst.PointSetCache;
import com.conveyal.r5.analyst.WorkerCategory;
import com.conveyal.r5.util.ExceptionUtils;
import org.joda.time.DateTime;
import org.json.simple.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import spark.Request;
import spark.Response;
import spark.Spark;

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Enumeration;

/**
 * This is the main entry point for starting a Conveyal Analysis server.
 */
public abstract class BackendMain {

    private static final Logger LOG = LoggerFactory.getLogger(BackendMain.class);

    /** If auto-shutdown=true, shut down after a period of no user activity. */
    private static final int IDLE_SHUTDOWN_MINUTES = 60;

    /** Keep track of the last time we received an authenticated (user-facing) API request. */
    private static long lastUserActivityTimeMsec = System.currentTimeMillis();

    /** This backend server's IP address. This is passed to the workers so they know how to reach the backend. */
    private static final InetAddress privateServerAddress = discoverPrivateInetAddress();

    public static void main (String... args) {
        final Components components = new LocalComponents();
        startServer(components);
    }

    protected static void startServer (Components components, Thread... postStartupThreads) {
        // We have several non-daemon background thread pools which will keep the JVM alive if the main thread crashes.
        // If initialization fails, we need to catch the exception or error and force JVM shutdown.
        try {
            startServerInternal(components, postStartupThreads);
        } catch (Throwable throwable) {
            LOG.error("Exception while starting up backend, shutting down JVM.\n{}", ExceptionUtils.asString(throwable));
            System.exit(1);
        }
    }

    private static void startServerInternal (Components components, Thread... postStartupThreads) {
        LOG.info("Starting Conveyal analysis backend, the time is now {}", DateTime.now());
        LOG.info("Backend version is: {}", BackendVersion.instance.version);
        LOG.info("Connecting to database...");

        // Persistence, the census extractor, and ApiMain are initialized statically, without creating instances,
        // passing in non-static components we've already created. TODO migrate to non-static Components.
        // TODO remove the static ApiMain abstraction layer. We do not use it anywhere but in handling GraphQL queries.
        Persistence.initializeStatically(components.config);
        SeamlessCensusGridExtractor.configureStatically(components.config);
        ApiMain.initialize(components.gtfsCache);
        PointSetCache.initializeStatically(components.fileStorage, components.config.gridBucket());

        if (components.config.offline()) {
            LOG.info("Running in OFFLINE mode.");
            LOG.info("Pre-starting local cluster of Analysis workers...");
            components.workerLauncher.launch(
                    new WorkerCategory(null, null), null, 1, 0);
        }

        LOG.info("Conveyal Analysis server is ready.");
        for (Thread thread : postStartupThreads) {
            thread.start();
        }

        if (components.config.immediateShutdown) {
            LOG.info("Startup has completed successfully. Exiting immediately as requested.");
            System.exit(0);
        }

        // TODO transform this into a task managed by a scheduled task executor component.
        if (components.config.autoShutdown) {
            LOG.info("Server will shut down automatically after {} minutes without user interaction.", IDLE_SHUTDOWN_MINUTES);
            while ((System.currentTimeMillis() - lastUserActivityTimeMsec) < (IDLE_SHUTDOWN_MINUTES * 60 * 1000)) {
                try {
                    Thread.sleep(60 * 1000);
                } catch (InterruptedException e) {
                    LOG.info("Shutdown wait loop was interrupted.");
                }
            }
            LOG.info("Shutting down backend server due to inactivity.");
            Spark.stop();
            System.exit(0);
        } else {
            LOG.info("Server will NOT shut down automatically, it will continue running indefinitely.");
        }
    }

    /**
     * Call this method any time something happens that should keep a staging server from shutting down.
     * This should include UI actions and incoming regional analysis results.
     */
    public static void recordActivityToPreventShutdown() {
        lastUserActivityTimeMsec = System.currentTimeMillis();
    }

    public static void respondToException(Exception e, Request request, Response response, String type, String message, int code) {
        String stack = ExceptionUtils.asString(e);

        LOG.error("{} {} -> {} {} by {} of {}", type, message, request.requestMethod(), request.pathInfo(), request.attribute("email"), request.attribute("accessGroup"));
        LOG.error(stack);

        JSONObject body = new JSONObject();
        body.put("type", type);
        body.put("message", message);
        body.put("stackTrace", stack);

        response.status(code);
        response.type("application/json");
        response.body(body.toJSONString());
    }

    public static String getServerIpAddress() {
        return privateServerAddress.getHostAddress();
    }

    // InetAddress.getLocalHost() fails on EC2 because the local hostname is not in the hosts file.
    // Anyway we don't want the default, we want to search for a stable, private interface internal to the cluster,
    // rather than the public one which may be reassigned during startup.
    // TODO move this to an InternalHttpApi Component.
    private static InetAddress discoverPrivateInetAddress() {
        InetAddress privateAddress = null;
        Enumeration<NetworkInterface> networkInterfaces = null;
        try {
            networkInterfaces = NetworkInterface.getNetworkInterfaces();
            while (networkInterfaces.hasMoreElements()) {
                NetworkInterface networkInterface = networkInterfaces.nextElement();
                try {
                    if (!networkInterface.isUp() || networkInterface.isLoopback()) {
                        continue;
                    }
                } catch (SocketException e) {
                    continue;
                }
                Enumeration<InetAddress> addressEnumeration = networkInterface.getInetAddresses();
                while (addressEnumeration.hasMoreElements()) {
                    InetAddress address = addressEnumeration.nextElement();
                    if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isMulticastAddress()) {
                        continue;
                    }
                    if (address.isSiteLocalAddress()) {
                        privateAddress = address;
                        break;
                    }
                }
            }
        } catch (SocketException e) {
            privateAddress = null;
        }
        if (privateAddress == null) {
            LOG.error("Could not determine private server IP address. Workers will not be able to contact it, making regional analysis impossible.");
            // privateAddress = InetAddress.getLoopbackAddress();
            // Leave the private address null to fail fast.
        } else {
            LOG.info("Private server IP address (which will be contacted by workers) is {}", privateAddress);
        }
        return privateAddress;
    }

}
