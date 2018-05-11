package com.conveyal.r5.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Map;

/**
 * Dummy request for GraphQL
 *
 * Since GraphQL request is in JSON Jackson needs this to parse it correctly.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class GraphQlRequest {
    public String query;
    public Map<String, Object> variables;
}
