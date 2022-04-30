package com.conveyal.analysis.controllers;

import com.conveyal.analysis.AnalysisServerException;
import com.conveyal.analysis.UserPermissions;
import com.conveyal.analysis.components.TokenAuthentication;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import spark.Request;
import spark.Response;

import java.lang.invoke.MethodHandles;

import static com.conveyal.analysis.AnalysisServerException.Type.UNAUTHORIZED;
import static com.conveyal.analysis.util.JsonUtil.toJson;
import static com.conveyal.r5.analyst.cluster.AnalysisWorker.sleepSeconds;

/**
 * HTTP API Controller that handles user accounts and authentication.
 * Serve up tokens for valid users. Allow admin users to create new users and set their passwords.
 * TODO add rate limiting and map size limiting (limit number of concurrent users in case of attacks).
 */
public class AuthTokenController implements HttpController {

    private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    private final TokenAuthentication tokenAuthentication;

    public AuthTokenController (TokenAuthentication tokenAuthentication) {
        this.tokenAuthentication = tokenAuthentication;
    }

    /**
     * Create a user with the specified password. Stores the random salt and hashed password in the database.
     */
    private Object createUser (Request req, Response res) {
        if (!UserPermissions.from(req).admin) {
            throw new AnalysisServerException(UNAUTHORIZED, "Only admin users can create new users.", 401);
        }
        String email = req.queryParams("email");
        String group = req.queryParams("group");
        String password = req.queryParams("password");
        tokenAuthentication.createUser(email, group, password);
        res.status(201);
        return "CREATED"; // alternatively UPDATED or FAILED
    }

    /**
     * Create a new token, replacing any existing one for the same user (email).
     */
    private TokenAuthentication.Token getTokenForEmail (Request req, Response res) {
        String email = req.queryParams("email");
        String password = req.queryParams("password");
        // Crude rate limiting, might just lead to connections piling up in event of attack.
        // sleepSeconds(2);
        TokenAuthentication.Token token = tokenAuthentication.getTokenForEmail(email, password);
        if (token == null) {
            throw new AnalysisServerException(UNAUTHORIZED, "Incorrect email/password combination.", 401);
        } else {
            return token;
        }
    }

    // Testing with Apache bench shows some stalling
    // -k keepalive connections fails immediately

    // Example usage:
    // curl -H "authorization: sesame" -X POST "localhost:7070/api/user?email=abyrd@conveyal.com&group=local&password=testpass"
    // 201 CREATED
    // curl "localhost:7070/token?email=abyrd@conveyal.com&password=testpass"
    // 200 {"token":"LHKUz6weI32mEk3SXBfGZFvPP3P9FZq8xboJdPPBIdo="}
    // curl -H "authorization: abyrd@conveyal.com Jx5Re2/fl1AAISeeMzaCJOy8OCRO6MVOAJLSN7/tkSg=" "localhost:7070/api/activity"
    // 200 {"systemStatusMessages":[],"taskBacklog":0,"taskProgress":[]}

    @Override
    public void registerEndpoints (spark.Service sparkService) {
        // Token endpoint is outside authenticated /api prefix because it's the means to get authentication tokens.
        sparkService.get("/token", this::getTokenForEmail, toJson);
        // User endpoint is inside the authenticated /api prefix because it is only accessible to admin users.
        sparkService.post("/api/user", this::createUser);
    }

}
