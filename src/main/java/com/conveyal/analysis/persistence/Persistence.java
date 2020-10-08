package com.conveyal.analysis.persistence;

import com.conveyal.analysis.models.AggregationArea;
import com.conveyal.analysis.models.Bundle;
import com.conveyal.analysis.models.JsonViews;
import com.conveyal.analysis.models.Model;
import com.conveyal.analysis.models.Modification;
import com.conveyal.analysis.models.OpportunityDataset;
import com.conveyal.analysis.models.Project;
import com.conveyal.analysis.models.Region;
import com.conveyal.analysis.models.RegionalAnalysis;
import com.conveyal.analysis.util.JsonUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.DB;
import com.mongodb.DBCollection;
import com.mongodb.MongoClient;
import com.mongodb.MongoClientURI;
import org.mongojack.JacksonDBCollection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages a single connection to MongoDB for the entire Conveyal Analysis backend.
 * Stores and retrieves objects of various types to their corresponding Mongo collections.
 *
 * TODO migrate to AnalysisDB class, which uses POJO storage from newer Mongo Java client library
 * TODO normalize database collection names, some are using kebab case and others java identifier case.
 *      we may also want to rename OpportunityDatasets to opportunity-grids (and opportunity-freeform)
 */
public class Persistence {

    private static final Logger LOG = LoggerFactory.getLogger(Persistence.class);

    public static MongoClient mongo;

    private static DB db;

    public static MongoMap<Modification> modifications;
    public static MongoMap<Project> projects;
    public static MongoMap<Bundle> bundles;
    public static MongoMap<Region> regions;
    public static MongoMap<RegionalAnalysis> regionalAnalyses;
    public static MongoMap<AggregationArea> aggregationAreas;
    public static MongoMap<OpportunityDataset> opportunityDatasets;

    // TODO progressively migrate to AnalysisDB which is non-static
    public static void initializeStatically (AnalysisDB.Config config) {
        LOG.info("Connecting to MongoDB...");
        if (config.databaseUri() != null) {
            LOG.info("Connecting to remote MongoDB instance...");
            mongo = new MongoClient(new MongoClientURI(config.databaseUri()));
        } else {
            LOG.info("Connecting to local MongoDB instance...");
            mongo = new MongoClient();
        }
        db = mongo.getDB(config.databaseName());
        modifications = getTable("modifications", Modification.class);
        projects = getTable("projects", Project.class);
        bundles = getTable("bundles", Bundle.class);
        regions = getTable("regions", Region.class);
        regionalAnalyses = getTable("regional-analyses", RegionalAnalysis.class);
        aggregationAreas = getTable("aggregationAreas", AggregationArea.class);
        opportunityDatasets = getTable("opportunityDatasets", OpportunityDataset.class);
    }

    /** Connect to a Mongo table using MongoJack, which persists Java objects into Mongo. */
    private static <V extends Model> MongoMap<V> getTable (String name, Class clazz) {
        DBCollection collection = db.getCollection(name);
        ObjectMapper om = JsonUtil.getObjectMapper(JsonViews.Db.class, true);
        JacksonDBCollection<V, String> coll = JacksonDBCollection.wrap(collection, clazz, String.class, om);
        return new MongoMap<>(coll, clazz);
    }

}
