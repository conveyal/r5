package com.conveyal.r5.api;

/**
 * Dummy request for GraphQL
 *
 * Since GraphQL request is in JSON Jackson needs this to parse it correctly.
 */
public class GraphQlRequest {
    public String query;
    //FIXME: This should be Map<String, Object> and be serialized automatically with Jackson but it
    //doesn't seems to work.
    public String variables;
}
