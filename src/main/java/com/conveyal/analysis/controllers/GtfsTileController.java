package com.conveyal.analysis.controllers;

import com.conveyal.gtfs.GTFSCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import spark.Request;
import spark.Response;
import spark.Service;

public class GtfsTileController implements HttpController {

    private static final Logger LOG = LoggerFactory.getLogger(GtfsTileController.class);

    private final GTFSCache gtfsCache;

    public GtfsTileController (GTFSCache gtfsCache) {
        this.gtfsCache = gtfsCache;
    }

    @Override
    public void registerEndpoints (Service sparkService) {
        // Ideally we'd have region, project, bundle, and worker version parameters.
        // Those could be path parameters, x-conveyal-headers, etc.
        sparkService.get("/api/bundles/:bundle/vectorTiles/:x/:y/:z", this::getTile);
    }

    private Object getTile (Request request, Response response) {
        return null;
    }

}