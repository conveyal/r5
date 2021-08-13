package com.conveyal.analysis.controllers;

import com.conveyal.analysis.UserPermissions;
import spark.Request;

/**
 * All of our classes defining HTTP API endpoints implement this interface.
 * It has a single method that registers all the endpoints.
 * For most controllers, all those endpoints will be under a single base URL path.
 * Each controller implementation should have a constructor taking all the application Components it needs as arguments.
 */
public interface HttpController {

    void registerEndpoints (spark.Service sparkService);

}
