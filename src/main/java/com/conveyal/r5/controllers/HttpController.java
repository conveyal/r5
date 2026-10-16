package com.conveyal.r5.controllers;

/**
 * All of our classes defining HTTP API endpoints implement this interface.
 * It has a single method that registers all the endpoints.
 * For most controllers, all those endpoints will be under a single base URL path.
 * Each controller implementation should have a constructor taking all the application Components it needs as arguments.
 */
public interface HttpController {

    /** Cache-Control header value for responses whose content will never change at a given URL. */
    String CACHE_CONTROL_IMMUTABLE = "public, max-age=2592000, immutable";

    void registerEndpoints (spark.Service sparkService);

}
