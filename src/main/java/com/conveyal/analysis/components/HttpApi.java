package com.conveyal.analysis.components;

import com.conveyal.analysis.AnalysisServerException;
import com.conveyal.r5.SoftwareVersion;
import com.conveyal.analysis.UserPermissions;
import com.conveyal.analysis.components.eventbus.ErrorEvent;
import com.conveyal.analysis.components.eventbus.EventBus;
import com.conveyal.analysis.components.eventbus.HttpApiEvent;
import com.conveyal.analysis.controllers.HttpController;
import com.conveyal.analysis.util.JsonUtil;
import com.conveyal.file.FileStorage;
import org.apache.commons.fileupload.FileUploadException;
import org.json.simple.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import spark.Request;
import spark.Response;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static com.conveyal.analysis.AnalysisServerException.Type.BAD_REQUEST;
import static com.conveyal.analysis.AnalysisServerException.Type.RUNTIME;
import static com.conveyal.analysis.AnalysisServerException.Type.UNKNOWN;

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
        boolean offline (); // TODO remove this parameter, use different Components types instead
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
            res.header("Access-Control-Allow-Origin", "*");

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
                req.attribute(USER_GROUP_ATTRIBUTE, userPermissions.accessGroup);
            }
        });

        sparkService.after((req, res) -> {
            // Firing an event after the request allows us to report the response time,
            // but may fail to record requests experiencing authentication problems.
            Instant requestStartTime = req.attribute(REQUEST_START_TIME_ATTRIBUTE);
            Duration elapsed = Duration.between(requestStartTime, Instant.now());
            eventBus.send(new HttpApiEvent(req.requestMethod(), res.status(), req.pathInfo(), elapsed.toMillis())
                    .forUser(req.attribute(USER_PERMISSIONS_ATTRIBUTE)));
        });

        // Handle CORS preflight requests (which are OPTIONS requests).
        sparkService.options("/*", (req, res) -> {
            res.header("Access-Control-Allow-Methods", "GET,PUT,POST,DELETE,OPTIONS");
            res.header("Access-Control-Allow-Credentials", "true");
            res.header("Access-Control-Allow-Headers", "Accept,Authorization,Content-Type,Origin," +
                    "X-Requested-With,Content-Length,X-Conveyal-Access-Group"
            );
            return "OK";
        });

        // Allow client to fetch information about the backend build version.
        sparkService.get(
                "/version",
                (Request req, Response res) -> SoftwareVersion.instance,
                JsonUtil.objectMapper::writeValueAsString
        );

        // Can we consolidate all these exception handlers and get rid of the hard-wired "BAD_REQUEST" parameters?

        sparkService.exception(AnalysisServerException.class, (e, request, response) -> {
            // Include a stack trace, except when the error is known to be about unauthenticated or unauthorized access,
            // in which case we don't want to leak information about the server to people scanning it for weaknesses.
            if (e.type == AnalysisServerException.Type.UNAUTHORIZED ||
                e.type == AnalysisServerException.Type.FORBIDDEN
            ){
                JSONObject body = new JSONObject();
                body.put("type", e.type.toString());
                body.put("message", e.message);
                response.status(e.httpCode);
                response.type("application/json");
                response.body(body.toJSONString());
            } else {
                respondToException(e, request, response, e.type, e.message, e.httpCode);
            }
        });

        sparkService.exception(IOException.class, (e, request, response) -> {
            respondToException(e, request, response, BAD_REQUEST, e.toString(), 400);
        });

        sparkService.exception(FileUploadException.class, (e, request, response) -> {
            respondToException(e, request, response, BAD_REQUEST, e.toString(), 400);
        });

        sparkService.exception(NullPointerException.class, (e, request, response) -> {
            respondToException(e, request, response, UNKNOWN, e.toString(), 400);
        });

        sparkService.exception(RuntimeException.class, (e, request, response) -> {
            respondToException(e, request, response, RUNTIME, e.toString(), 400);
        });

        return sparkService;
    }

    private void respondToException(Exception e, Request request, Response response,
                                    AnalysisServerException.Type type, String message, int code) {

        // Stacktrace in ErrorEvent reused below to avoid repeatedly generating String of stacktrace.
        ErrorEvent errorEvent = new ErrorEvent(e);
        eventBus.send(errorEvent.forUser(request.attribute(USER_PERMISSIONS_ATTRIBUTE)));

        JSONObject body = new JSONObject();
        body.put("type", type.toString());
        body.put("message", message);
        body.put("stackTrace", errorEvent.stackTrace);

        response.status(code);
        response.type("application/json");
        response.body(body.toJSONString());
    }

    // Maybe this should be done or called with a JVM shutdown hook
    public void shutDown () {
        sparkService.stop();
    }

}
