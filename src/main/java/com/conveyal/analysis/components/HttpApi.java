package com.conveyal.analysis.components;

import com.conveyal.analysis.AnalysisServerException;
import com.conveyal.analysis.BackendMain;
import com.conveyal.analysis.BackendVersion;
import com.conveyal.analysis.UserPermissions;
import com.conveyal.analysis.components.eventbus.Event;
import com.conveyal.analysis.components.eventbus.EventBus;
import com.conveyal.analysis.components.eventbus.HttpApiEvent;
import com.conveyal.analysis.controllers.HttpController;
import com.conveyal.analysis.util.JsonUtil;
import com.conveyal.file.FileStorage;
import com.conveyal.file.FileStorageFormat;
import com.conveyal.file.FileStorageKey;
import com.conveyal.file.FileUtils;
import org.apache.commons.fileupload.FileUploadException;
import org.json.simple.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import spark.Request;
import spark.Response;

import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * This Component is a web server that serves up our HTTP API endpoints, both to the UI and to the workers.
 * It must be supplied with a list of HttpController instances implementing the endpoints.
 */
public class HttpApi implements Component {

    private static final Logger LOG = LoggerFactory.getLogger(HttpApi.class);

    // These "attributes" are attached to an incoming HTTP request with String keys, making them available in handlers
    private static final String REQUEST_START_TIME_ATTRIBUTE = "requestStartTime";
    public static final String USER_PERMISSIONS_ATTRIBUTE = "permissions";
    public static final String USER_EMAIL_ATTRIBUTE = "email";
    public static final String USER_GROUP_ATTRIBUTE = "accessGroup";

    public interface Config {
        boolean offline ();
        int serverPort ();
    }

    private final FileStorage fileStorage;
    private final Authentication authentication;
    private final EventBus eventBus;
    private final Config config;

    private final spark.Service sparkService;
    private final List<HttpController> httpControllers;

    public HttpApi (
            FileStorage fileStorage,
            Authentication authentication,
            EventBus eventBus,
            Config config,
            List<HttpController> httpControllers
    ){
        this.fileStorage = fileStorage;
        this.authentication = authentication;
        this.eventBus = eventBus;
        this.config = config;
        this.httpControllers = httpControllers;

        sparkService = configureSparkService();
        for (HttpController httpController : httpControllers) {
            httpController.registerEndpoints(sparkService);
        }
    }

    private spark.Service configureSparkService () {
        // Set up Spark, the HTTP framework wrapping Jetty, including the port on which it will listen for connections.
        LOG.info("Analysis server will listen for HTTP connections on port {}.", config.serverPort());
        spark.Service sparkService = spark.Service.ignite();
        sparkService.port(config.serverPort());

        // Specify actions to take before the main logic of handling each HTTP request.
        sparkService.before((req, res) -> {
            // Record when the request started, so we can measure elapsed response time.
            req.attribute(REQUEST_START_TIME_ATTRIBUTE, Instant.now());

            // Don't require authentication to view the main page, or for internal API endpoints contacted by workers.
            // FIXME those internal endpoints should be hidden from the outside world by the reverse proxy.
            //       Or now with non-static Spark we can run two HTTP servers on different ports.

            // Set CORS headers, to allow requests to this API server from any page.
            // but do not do this when running offline with no auth, as this may allow a malicious website to use a local
            // browser to access the analysis server.
            if (!config.offline()) {
                res.header("Access-Control-Allow-Origin", "*");
            }

            // The default MIME type is JSON. This will be overridden by the few controllers that do not return JSON.
            res.type("application/json");

            // Do not require authentication for internal API endpoints contacted by workers or for OPTIONS requests.
            String method = req.requestMethod();
            String pathInfo = req.pathInfo();
            boolean authorize = pathInfo.startsWith("/api") && !"OPTIONS".equalsIgnoreCase(method);
            if (authorize) {
                // Determine which user is sending the request, and which permissions that user has.
                // This method throws an exception if the user cannot be authenticated.
                // Store the resulting permissions object in the request so it can be examined by any handler.
                UserPermissions userPermissions = authentication.authenticate(req);
                req.attribute(USER_PERMISSIONS_ATTRIBUTE, userPermissions);
                // TODO stop using these two separate attributes, and use the permissions object directly
                req.attribute(USER_EMAIL_ATTRIBUTE, userPermissions.email);
                req.attribute(USER_GROUP_ATTRIBUTE, userPermissions.groups.iterator().next());
            }
        });

        sparkService.after((req, res) -> {
            // Firing an event after the request allows us to report the response time,
            // but may fail to record requests experiencing authentication problems.
            Instant requestStartTime = req.attribute(REQUEST_START_TIME_ATTRIBUTE);
            Duration elapsed = Duration.between(requestStartTime, Instant.now());
            Event event = new HttpApiEvent(req.requestMethod(), res.status(), req.pathInfo(), elapsed.toMillis());
            UserPermissions userPermissions = req.attribute(USER_PERMISSIONS_ATTRIBUTE);
            eventBus.send(event.forUser(userPermissions));
        });

        // Handle CORS preflight requests (which are OPTIONS requests).
        // except when running in offline mode (see above comment about auth and CORS)
        if (!config.offline()) {
            sparkService.options("/*", (req, res) -> {
                res.header("Access-Control-Allow-Methods", "GET,PUT,POST,DELETE,OPTIONS");
                res.header("Access-Control-Allow-Credentials", "true");
                res.header("Access-Control-Allow-Headers", "Accept,Authorization,Content-Type,Origin," +
                        "X-Requested-With,Content-Length,X-Conveyal-Access-Group"
                );
                return "OK";
            });
        }

        // Allow client to fetch information about the backend build version.
        sparkService.get(
                "/version",
                (Request req, Response res) -> BackendVersion.instance,
                JsonUtil.objectMapper::writeValueAsString
        );

        // Expose all files in storage while in offline mode.
        // Not done with static file serving because it automatically gzips our already gzipped files.
        if (config.offline()) {
            sparkService.get("/files/:bucket/*", (req, res) -> {
                String filename = req.splat()[0];
                FileStorageKey key = new FileStorageKey(req.params("bucket"), filename);
                File file = fileStorage.getFile(key);
                FileStorageFormat format = FileStorageFormat.fromFilename(filename);
                res.type(format.mimeType);

                // If the content-encoding is set to gzip, Spark automatically gzips the response. This mangles data
                // that was already gzipped. Therefore, check if it's gzipped and pipe directly to the raw OutputStream.
                res.header("Content-Encoding", "gzip");
                if (FileUtils.isGzip(file)) {
                    FileUtils.transferFromFileTo(file, res.raw().getOutputStream());
                    return null;
                } else {
                    return FileUtils.getInputStream(file);
                }
            });
        }

        // ============

        sparkService.exception(AnalysisServerException.class, (e, request, response) -> {
            // Include a stack trace, except when the error is known to be about unauthenticated or unauthorized access,
            // in which case we don't want to leak information about the server to people scanning it for weaknesses.
            if (e.type == AnalysisServerException.TYPE.UNAUTHORIZED ||
                e.type == AnalysisServerException.TYPE.FORBIDDEN
            ){
                JSONObject body = new JSONObject();
                body.put("type", e.type.toString());
                body.put("message", e.message);
                response.status(e.httpCode);
                response.type("application/json");
                response.body(body.toJSONString());
            } else {
                BackendMain.respondToException(e, request, response, e.type.name(), e.message, e.httpCode);
            }
        });

        sparkService.exception(IOException.class, (e, request, response) -> {
            BackendMain.respondToException(e, request, response, "BAD_REQUEST", e.toString(), 400);
        });

        sparkService.exception(FileUploadException.class, (e, request, response) -> {
            BackendMain.respondToException(e, request, response, "BAD_REQUEST", e.toString(), 400);
        });

        sparkService.exception(NullPointerException.class, (e, request, response) -> {
            BackendMain.respondToException(e, request, response, "UNKNOWN", e.toString(), 400);
        });

        sparkService.exception(RuntimeException.class, (e, request, response) -> {
            BackendMain.respondToException(e, request, response, "RUNTIME", e.toString(), 400);
        });

        return sparkService;
    }

    // Maybe this should be done or called with a JVM shutdown hook
    public void shutDown () {
        sparkService.stop();
    }

}
