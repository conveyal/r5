package com.conveyal.r5.api;

/**
 * Dummy request for GraphQL
 *
 * Since GraphQL request is in JSON Jackson needs this to parse it correctly.
 */
public class GraphQlRequest {
    public String query;
    public String variables;
}
