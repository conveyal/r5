package com.conveyal.analysis.controllers;

import com.conveyal.analysis.UserPermissions;
import com.conveyal.analysis.components.TaskScheduler;
import com.conveyal.analysis.datasource.derivation.AggregationAreaDerivation;
import com.conveyal.analysis.datasource.derivation.DataDerivation;
import com.conveyal.analysis.models.AggregationArea;
import com.conveyal.analysis.models.DataSource;
import com.conveyal.analysis.persistence.AnalysisCollection;
import com.conveyal.analysis.persistence.AnalysisDB;
import com.conveyal.analysis.util.JsonUtil;
import com.conveyal.file.FileStorage;
import com.conveyal.file.UrlWithHumanName;
import com.conveyal.r5.analyst.progress.Task;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.bson.conversions.Bson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import spark.Request;
import spark.Response;

import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static com.conveyal.analysis.util.JsonUtil.toJson;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;
import static org.eclipse.jetty.http.MimeTypes.Type.APPLICATION_JSON;

/**
 * Stores vector aggregationAreas (used to define the region of a weighted average accessibility metric).
 */
public class AggregationAreaController implements HttpController {

    private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    private final FileStorage fileStorage;
    private final AnalysisDB analysisDb;
    private final TaskScheduler taskScheduler;

    private final AnalysisCollection<DataSource> dataSourceCollection;
    private final AnalysisCollection<AggregationArea> aggregationAreaCollection;

    public AggregationAreaController (
            FileStorage fileStorage,
            AnalysisDB database,
            TaskScheduler taskScheduler
    ) {
        this.fileStorage = fileStorage;
        this.analysisDb = database;
        this.taskScheduler = taskScheduler;
        dataSourceCollection = database.getAnalysisCollection("dataSources", DataSource.class);
        aggregationAreaCollection = database.getAnalysisCollection("aggregationAreas", AggregationArea.class);
    }

    /**
     * Create binary .grid files for aggregation (aka mask) areas, save them to FileStorage, and persist their metadata
     * to Mongo. The supplied request (req) must include query parameters specifying the dataSourceId of a
     * SpatialDataSoure containing the polygonal aggregation area geometries. If the mergePolygons query parameter is
     * supplied and is true, all polygons will be merged into one large (multi)polygon aggregation area.
     * If the mergePolygons query parameter is not supplied or is false, the nameProperty query parameter must be
     * the name of a text attribute in that SpatialDataSource. One aggregation area will be created for each polygon
     * drawing the names from that attribute.
     * @return the ID of the Task representing the enqueued background action that will create the aggregation areas.
     */
    private String createAggregationAreas (Request req, Response res) throws Exception {
        // Create and enqueue an asynchronous background action to derive aggreagation areas from spatial data source.
        // The constructor will extract query parameters and range check them (not ideal separation, but it works).
        DataDerivation derivation = AggregationAreaDerivation.fromRequest(req, fileStorage, analysisDb);
        Task backgroundTask = Task.create("Aggregation area creation: " + derivation.dataSource().name)
                .forUser(UserPermissions.from(req))
                .setHeavy(true)
                .withAction(derivation);

        taskScheduler.enqueue(backgroundTask);
        return backgroundTask.id.toString();
    }

    /**
     * Get all aggregation area documents meeting the supplied criteria.
     * The request must contain a query parameter for the regionId or the dataGroupId or both.
     */
    private Collection<AggregationArea> getAggregationAreas (Request req, Response res) {
        List<Bson> filters = new ArrayList<>();
        String regionId = req.queryParams("regionId");
        if (regionId != null) {
            filters.add(eq("regionId", regionId));
        }
        String dataGroupId = req.queryParams("dataGroupId");
        if (dataGroupId != null) {
            filters.add(eq("dataGroupId", dataGroupId));
        }
        if (filters.isEmpty()) {
            throw new IllegalArgumentException("You must supply either a regionId or a dataGroupId or both.");
        }
        return aggregationAreaCollection.findPermitted(and(filters), UserPermissions.from(req));
    }

    /** Returns a JSON-wrapped URL for the mask grid of the aggregation area whose id matches the path parameter. */
    private UrlWithHumanName getAggregationAreaGridUrl (Request req, Response res) {
        AggregationArea aggregationArea = aggregationAreaCollection.findPermittedByRequestParamId(req);
        res.type(APPLICATION_JSON.asString());
        return fileStorage.getJsonUrl(aggregationArea.getStorageKey(), aggregationArea.name, "grid");
    }

    @Override
    public void registerEndpoints (spark.Service sparkService) {
        sparkService.get("/api/aggregationArea", this::getAggregationAreas, toJson);
        sparkService.get("/api/aggregationArea/:_id", this::getAggregationAreaGridUrl, toJson);
        sparkService.post("/api/aggregationArea", this::createAggregationAreas, toJson);
    }

}
