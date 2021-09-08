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
import com.conveyal.r5.analyst.progress.Task;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.bson.conversions.Bson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import spark.Request;
import spark.Response;

import java.lang.invoke.MethodHandles;
import java.util.Collection;

import static com.conveyal.analysis.util.JsonUtil.toJson;
import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;

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
     * SpatialDataSoure containing the polygonal aggregation area geometries. If the nameProperty query parameter is
     * non-null, it must be the name of a text attribute in that SpatialDataSource, and one aggregation area will be
     * created for each polygon using those names. If the nameProperty is not supplied, all polygons will be merged into
     * one large nameless (multi)polygon aggregation area.
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

    private Collection<AggregationArea> getAggregationAreas (Request req, Response res) {
        Bson query = eq("regionId", req.queryParams("regionId"));
        String dataGroupId = req.queryParams("dataGroupId");
        if (dataGroupId != null) {
            query = and(eq("dataGroupId", dataGroupId), query);
        }
        return aggregationAreaCollection.findPermitted(query, UserPermissions.from(req));
    }

    private ObjectNode getAggregationArea (Request req, Response res) {
        AggregationArea aggregationArea = aggregationAreaCollection.findByIdIfPermitted(
                req.params("maskId"), UserPermissions.from(req)
        );
        String url = fileStorage.getURL(aggregationArea.getStorageKey());
        return JsonUtil.objectNode().put("url", url);

    }

    @Override
    public void registerEndpoints (spark.Service sparkService) {
        sparkService.path("/api/region/", () -> {
            sparkService.get("/:regionId/aggregationArea", this::getAggregationAreas, toJson);
            sparkService.get("/:regionId/aggregationArea/:maskId", this::getAggregationArea, toJson);
            sparkService.post("/:regionId/aggregationArea", this::createAggregationAreas, toJson);
        });
    }

}
