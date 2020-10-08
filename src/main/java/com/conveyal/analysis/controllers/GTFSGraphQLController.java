package com.conveyal.analysis.controllers;

import com.conveyal.analysis.AnalysisServerException;
import com.conveyal.analysis.models.Bundle;
import com.conveyal.analysis.persistence.Persistence;
import com.conveyal.analysis.util.JsonUtil;
import com.conveyal.gtfs.GTFSCache;
import com.conveyal.gtfs.GTFSFeed;
import com.conveyal.gtfs.api.graphql.WrappedGTFSEntity;
import com.conveyal.gtfs.api.graphql.fetchers.RouteFetcher;
import com.conveyal.gtfs.api.graphql.fetchers.StopFetcher;
import com.conveyal.gtfs.model.FeedInfo;
import com.fasterxml.jackson.core.type.TypeReference;
import com.google.common.util.concurrent.UncheckedExecutionException;
import com.mongodb.QueryBuilder;
import graphql.ExceptionWhileDataFetching;
import graphql.ExecutionResult;
import graphql.GraphQL;
import graphql.GraphQLError;
import graphql.execution.ExecutionContext;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.GraphQLEnumType;
import graphql.schema.GraphQLList;
import graphql.schema.GraphQLObjectType;
import graphql.schema.GraphQLSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import spark.Request;
import spark.Response;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.conveyal.analysis.controllers.BundleController.setBundleServiceDates;
import static com.conveyal.analysis.util.JsonUtil.toJson;
import static com.conveyal.gtfs.api.graphql.GraphQLGtfsSchema.routeType;
import static com.conveyal.gtfs.api.graphql.GraphQLGtfsSchema.stopType;
import static com.conveyal.gtfs.api.util.GraphQLUtil.multiStringArg;
import static com.conveyal.gtfs.api.util.GraphQLUtil.string;
import static graphql.Scalars.GraphQLLong;
import static graphql.schema.GraphQLEnumType.newEnum;
import static graphql.schema.GraphQLFieldDefinition.newFieldDefinition;
import static graphql.schema.GraphQLObjectType.newObject;

/**
 * GraphQL interface for fetching GTFS feed contents (generally used for scenario editing).
 * For now it just wraps the GTFS API graphql response with a bundle object.
 */
public class GTFSGraphQLController implements HttpController {

    private static final Logger LOG = LoggerFactory.getLogger(GTFSGraphQLController.class);

    private final GTFSCache gtfsCache;

    public GTFSGraphQLController (GTFSCache gtfsCache) {
        this.gtfsCache = gtfsCache;
    }

    private Object handleQuery (Request req, Response res) throws IOException {
        res.type("application/json");

        Map<String, Object> variables = JsonUtil.objectMapper.readValue(req.queryParams("variables"), new TypeReference<Map<String, Object>>() {
        });

        QueryContext context = new QueryContext();
        context.accessGroup = req.attribute("accessGroup");

        ExecutionResult er = graphql.execute(req.queryParams("query"), null, context, variables);

        List<GraphQLError> errs = er.getErrors();
        errs.addAll(context.getErrors());
        if (!errs.isEmpty()) {
            throw AnalysisServerException.graphQL(errs);
        }

        return er.getData();
    }

    /** Special feed type that also includes checksum */
    public GraphQLObjectType feedType = newObject()
            .name("feed")
            .field(string("feed_id"))
            .field(string("feed_publisher_name"))
            .field(string("feed_publisher_url"))
            .field(string("feed_lang"))
            .field(string("feed_version"))
            // We have a custom wrapped GTFS Entity type for FeedInfo that includes feed checksum
            .field(newFieldDefinition()
                    .name("checksum")
                    .type(GraphQLLong)
                    .dataFetcher(env -> ((WrappedFeedInfo) env.getSource()).checksum)
                    .build()
            )
            .field(newFieldDefinition()
                    .name("routes")
                    .type(new GraphQLList(routeType))
                    .argument(multiStringArg("route_id"))
                    .dataFetcher(RouteFetcher::fromFeed)
                    .build()
            )
            .field(newFieldDefinition()
                    .name("stops")
                    .type(new GraphQLList(stopType))
                    .dataFetcher(StopFetcher::fromFeed)
                    .build()
            )
            .build();

    private GraphQLEnumType bundleStatus = newEnum()
            .name("status")
            .value("PROCESSING_GTFS", Bundle.Status.PROCESSING_GTFS)
            .value("PROCESSING_OSM", Bundle.Status.PROCESSING_OSM)
            .value("ERROR", Bundle.Status.ERROR)
            .value("DONE", Bundle.Status.DONE)
            .build();

    private GraphQLObjectType bundleType = newObject()
            .name("bundle")
            .field(string("_id"))
            .field(string("name"))
            .field(newFieldDefinition()
                    .name("status")
                    .type(bundleStatus)
                    .dataFetcher((env) -> ((Bundle) env.getSource()).status)
                    .build()
            )
            .field(newFieldDefinition()
                    .name("feeds")
                    .type(new GraphQLList(feedType))
                    .dataFetcher(this::fetchFeeds)
                    .build()
            )
            .build();

    private GraphQLObjectType bundleQuery = newObject()
            .name("bundleQuery")
            .field(newFieldDefinition()
                    .name("bundle")
                    .type(new GraphQLList(bundleType))
                    .argument(multiStringArg("bundle_id"))
                    .dataFetcher(this::fetchBundle)
                    .build()
            )
            .build();

    public GraphQLSchema schema = GraphQLSchema.newSchema().query(bundleQuery).build();
    private GraphQL graphql = new GraphQL(schema);

    private Collection<Bundle> fetchBundle(DataFetchingEnvironment environment) {
        QueryContext context = (QueryContext) environment.getContext();
        return Persistence.bundles.findPermitted(
                QueryBuilder.start("_id").in(environment.getArgument("bundle_id")).get(),
                context.accessGroup
        );
    }

    /**
     * Returns a list of wrapped FeedInfo objects. These objects have all fields null (default), except feed_id, which
     * is set from the cached copy of each requested feed. This method previously returned the fully populated FeedInfo
     * objects, but incremental changes led to incompatibilities with Analysis (see analysis-internal #102). The
     * current implementation is a stopgap for backward compatibility.
     */
    private List<WrappedGTFSEntity<FeedInfo>> fetchFeeds(DataFetchingEnvironment environment) {
        Bundle bundle = (Bundle) environment.getSource();
        ExecutionContext context = (ExecutionContext) environment.getContext();

        // Old bundles were created without computing the service start and end dates. Will only compute if needed.
        try {
            setBundleServiceDates(bundle, gtfsCache);
        } catch (Exception e) {
            context.addError(new ExceptionWhileDataFetching(e));
        }

        return bundle.feeds.stream()
                .map(summary -> {
                    String bundleScopedFeedId = Bundle.bundleScopeFeedId(summary.feedId, bundle.feedGroupId);
                    try {
                        GTFSFeed feed = gtfsCache.get(bundleScopedFeedId);
                        FeedInfo ret = new FeedInfo();
                        ret.feed_id = feed.feedId;
                        return new WrappedFeedInfo(summary.bundleScopedFeedId, ret, summary.checksum);
                    } catch (UncheckedExecutionException nsee) {
                        Exception e = new Exception(String.format("Feed %s does not exist in the cache.", summary.name), nsee);
                        context.addError(new ExceptionWhileDataFetching(e));
                        return null;
                    } catch (Exception e) {
                        context.addError(new ExceptionWhileDataFetching(e));
                        return null;
                    }
                })
                .collect(Collectors.toList());
    }

    @Override
    public void registerEndpoints (spark.Service sparkService) {
        // TODO make this `post` as per GraphQL convention
        sparkService.get("/api/graphql", this::handleQuery, toJson);
    }

    /** Context for a graphql query. Currently contains authorization info. */
    public static class QueryContext extends ExecutionContext {
        public String accessGroup;
    }

}
