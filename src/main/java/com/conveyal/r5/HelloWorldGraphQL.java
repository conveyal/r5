package com.conveyal.r5;

import com.conveyal.r5.api.ProfileResponse;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.ObjectMapper;
import graphql.ExecutionResult;
import graphql.GraphQL;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.Map;

import static graphql.Scalars.GraphQLString;
import static graphql.schema.GraphQLFieldDefinition.newFieldDefinition;
import static graphql.schema.GraphQLObjectType.newObject;

public class HelloWorldGraphQL {
    private static final Logger LOG = LoggerFactory.getLogger(HelloWorldGraphQL.class);

    public static void main(String[] args) throws IOException {

        ProfileResponse profileResponse = new DummyProfile().profileResponse;

        String query = "  query IntrospectionQuery {\n"
            + "    __schema {\n"
            + "      queryType { name }\n"
            + "      mutationType { name }\n"
            + "      types {\n"
            + "        ...FullType\n"
            + "      }\n"
            + "      directives {\n"
            + "        name\n"
            + "        description\n"
            + "        args {\n"
            + "          ...InputValue\n"
            + "        }\n"
            + "        onOperation\n"
            + "        onFragment\n"
            + "        onField\n"
            + "      }\n"
            + "    }\n"
            + "  }\n"
            + "\n"
            + "  fragment FullType on __Type {\n"
            + "    kind\n"
            + "    name\n"
            + "    description\n"
            + "    fields {\n"
            + "      name\n"
            + "      description\n"
            + "      args {\n"
            + "        ...InputValue\n"
            + "      }\n"
            + "      type {\n"
            + "        ...TypeRef\n"
            + "      }\n"
            + "      isDeprecated\n"
            + "      deprecationReason\n"
            + "    }\n"
            + "    inputFields {\n"
            + "      ...InputValue\n"
            + "    }\n"
            + "    interfaces {\n"
            + "      ...TypeRef\n"
            + "    }\n"
            + "    enumValues {\n"
            + "      name\n"
            + "      description\n"
            + "      isDeprecated\n"
            + "      deprecationReason\n"
            + "    }\n"
            + "    possibleTypes {\n"
            + "      ...TypeRef\n"
            + "    }\n"
            + "  }\n"
            + "\n"
            + "  fragment InputValue on __InputValue {\n"
            + "    name\n"
            + "    description\n"
            + "    type { ...TypeRef }\n"
            + "    defaultValue\n"
            + "  }\n"
            + "\n"
            + "  fragment TypeRef on __Type {\n"
            + "    kind\n"
            + "    name\n"
            + "    ofType {\n"
            + "      kind\n"
            + "      name\n"
            + "      ofType {\n"
            + "        kind\n"
            + "        name\n"
            + "        ofType {\n"
            + "          kind\n"
            + "          name\n"
            + "        }\n"
            + "      }\n"
            + "    }\n"
            + "  }";
        query = "{profile (from: \"A\",  to: \"B\"){options {summary}}}";
        //query = "{ fare { type, low transferReduction} }";
        //GraphQL graphQL = new GraphQL(new com.conveyal.r5.GraphQLSchema(profileResponse).indexSchema), Executors.newC
        ExecutionResult result = new GraphQL(new com.conveyal.r5.GraphQLSchema(profileResponse).indexSchema)
            .execute(query);
        if (!result.getErrors().isEmpty()) {
            LOG.error("Errors:{}", result.getErrors());
        } else {
            LOG.info("{}", result.getData());
        }

    }
}