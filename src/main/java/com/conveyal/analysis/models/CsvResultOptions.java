package com.conveyal.analysis.models;

import com.conveyal.r5.transit.TransitLayer.EntityRepresentation;

import static com.conveyal.r5.transit.TransitLayer.EntityRepresentation.ID_ONLY;

/**
 * API model type included in analysis requests to control details of CSV regional analysis output.
 * This type is shared between AnalysisRequest (Frontend -> Broker) and AnalysisWorkerTask (Broker -> Workers).
 * There is precedent for nested compound types shared across those top level request types (see DecayFunction).
 */
public class CsvResultOptions {
    public EntityRepresentation routeRepresentation = ID_ONLY;
    public EntityRepresentation stopRepresentation = ID_ONLY;
    // Only feed ID representation is allowed to be null (no feed IDs at all, the default).
    public EntityRepresentation feedRepresentation = null;
}
