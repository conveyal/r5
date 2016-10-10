package com.conveyal.r5.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Dummy request for GraphQL
 *
 * Since GraphQL request is in JSON Jackson needs this to parse it correctly.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class GraphQlRequest {
    public String query;
    //FIXME: This should be Map<String, Object> and be serialized automatically with Jackson but it
    //doesn't seems to work.
    public String variables;
}
