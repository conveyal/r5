package com.conveyal.r5;

import com.conveyal.r5.api.GraphQlRequest;
import com.conveyal.r5.api.ProfileResponse;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import graphql.ExecutionResult;
import graphql.GraphQL;
import graphql.GraphQLException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;

import static spark.Spark.*;

/**
 * Created by mabu on 2.11.2015.
 */
public class GraphQlSpark {
    private static final Logger LOG = LoggerFactory.getLogger(GraphQlSpark.class);

    public static String dataToJson(Object data) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            mapper.enable(SerializationFeature.INDENT_OUTPUT);
            StringWriter sw = new StringWriter();
            mapper.writeValue(sw, data);
            return sw.toString();
        } catch (IOException e){
            throw new RuntimeException("IOException from a StringWriter?");
        }
    }

    public static void main(String[] args) throws IOException {

        ObjectMapper mapper = new ObjectMapper();
        ProfileResponse profileResponse = mapper.readValue(
            GraphQlSpark.class.getResourceAsStream("/profile.json"), ProfileResponse.class);

        GraphQL graphQL = new GraphQL(new com.conveyal.r5.GraphQLSchema(profileResponse).indexSchema);

        port(8080);

        post("/otp/routers/default/index/graphql", ((request, response) -> {
            response.type("application/json");

            HashMap<String, Object> content = new HashMap<>();
            try {
                GraphQlRequest graphQlRequest = mapper
                    .readValue(request.body(), GraphQlRequest.class);

                Map<String, Object> variables = new HashMap<>();
                ExecutionResult executionResult = graphQL.execute(graphQlRequest.query);
                response.status(200);

                if (!executionResult.getErrors().isEmpty()) {
                    response.status(500);
                    content.put("errors", executionResult.getErrors());
                }
                if (executionResult.getData() != null) {
                    content.put("data", executionResult.getData());
                }
            } catch (JsonParseException jpe) {
                response.status(400);
                content.put("errors", "Problem parsing query: " + jpe.getMessage());
            } catch (GraphQLException ql) {
                response.status(500);
                content.put("errors", ql.getMessage());
                LOG.error("GraphQL problem:", ql);
            } finally {
                return dataToJson(content);
            }

            }));

            //CORS:
            options("/*", (request, response) -> {

                String accessControlRequestHeaders = request
                    .headers("Access-Control-Request-Headers");
                if (accessControlRequestHeaders != null) {
                    response.header("Access-Control-Allow-Headers", accessControlRequestHeaders);
                }

                String accessControlRequestMethod = request
                    .headers("Access-Control-Request-Method");
                if (accessControlRequestMethod != null) {
                    response.header("Access-Control-Allow-Methods", accessControlRequestMethod);
                }

                return "OK";
            });

        before((request, response) -> {
            response.header("Access-Control-Allow-Origin", "http://localhost:8000");
            });

        }
    }
