package com.conveyal.analysis.persistence;

import com.conveyal.analysis.components.Component;
import com.conveyal.analysis.models.AddStreets;
import com.conveyal.analysis.models.AddTripPattern;
import com.conveyal.analysis.models.AdjustDwellTime;
import com.conveyal.analysis.models.AdjustSpeed;
import com.conveyal.analysis.models.AggregationArea;
import com.conveyal.analysis.models.BaseModel;
import com.conveyal.analysis.models.Bundle;
import com.conveyal.analysis.models.ConvertToFrequency;
import com.conveyal.analysis.models.DataGroup;
import com.conveyal.analysis.models.DataSource;
import com.conveyal.analysis.models.GtfsDataSource;
import com.conveyal.analysis.models.Modification;
import com.conveyal.analysis.models.ModifyStreets;
import com.conveyal.analysis.models.OpportunityDataset;
import com.conveyal.analysis.models.OsmDataSource;
import com.conveyal.analysis.models.Region;
import com.conveyal.analysis.models.RegionalAnalysis;
import com.conveyal.analysis.models.RemoveStops;
import com.conveyal.analysis.models.RemoveTrips;
import com.conveyal.analysis.models.Reroute;
import com.conveyal.analysis.models.SpatialDataSource;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoDatabase;
import org.bson.codecs.configuration.CodecProvider;
import org.bson.codecs.configuration.CodecRegistries;
import org.bson.codecs.configuration.CodecRegistry;
import org.bson.codecs.pojo.PojoCodecProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Component to handle the backend configuration and usage of our database. Configures MonoDB to use specific "Codecs"
 * for converting data from a format MongoDB recognizes into their Java representation. Note: POJOs do not need special
 * configuration of their own.
 */
public class AnalysisDB implements Component {
    private final Logger LOG = LoggerFactory.getLogger(AnalysisDB.class);
    private final MongoDatabase database;

    public final AnalysisCollection<AggregationArea> aggregationAreas;
    public final AnalysisCollection<Bundle> bundles;
    public final AnalysisCollection<DataGroup> dataGroups;
    public final AnalysisCollection<DataSource> dataSources;
    public final AnalysisCollection<Modification> modifications;
    public final AnalysisCollection<OpportunityDataset> opportunities;
    public final AnalysisCollection<Region> regions;
    public final AnalysisCollection<RegionalAnalysis> regionalAnalyses;

    public AnalysisDB(Config config) {
        MongoClient mongoClient;
        if (config.databaseUri() != null) {
            LOG.info("Connecting to remote MongoDB instance...");
            mongoClient = MongoClients.create(config.databaseUri());
        } else {
            LOG.info("Connecting to local MongoDB instance...");
            mongoClient = MongoClients.create();
        }
        // Request that the JVM clean up database connections in all cases - exiting cleanly or by being terminated.
        // We should probably register such hooks for other components to shut down more cleanly.
        Runtime.getRuntime().addShutdownHook(new Thread(mongoClient::close));

        database = mongoClient.getDatabase(config.databaseName()).withCodecRegistry(makeCodecRegistry());

        aggregationAreas = getAnalysisCollection("aggregationAreas", AggregationArea.class);
        bundles = getAnalysisCollection("bundles", Bundle.class);
        dataGroups = getAnalysisCollection("dataGroups", DataGroup.class);

        addSubclassesToRegistry(SpatialDataSource.class, OsmDataSource.class, GtfsDataSource.class);
        dataSources = getAnalysisCollection("dataSources", DataSource.class);

        addSubclassesToRegistry(AddStreets.class, AddTripPattern.class, AdjustDwellTime.class, AdjustSpeed.class, ConvertToFrequency.class, ModifyStreets.class, RemoveStops.class, RemoveTrips.class, Reroute.class);
        modifications = getAnalysisCollection("modifications", Modification.class);
        opportunities = getAnalysisCollection("opportunityDatasets", OpportunityDataset.class);
        regions = getAnalysisCollection("regions", Region.class);
        regionalAnalyses = getAnalysisCollection("regional-analyses", RegionalAnalysis.class);
    }

    /**
     * Create a codec registry that has all the default codecs (dates, GeoJSON, etc.) and falls back to a provider
     * that automatically generates codecs for any other Java class it encounters, based on their public getter and
     * setter methods and public fields, skipping any properties whose underlying fields are transient or static.
     * These classes must have an empty public or protected zero-argument constructor.
     * An automatic PojoCodecProvider can create class models and codecs on the fly as it encounters the classes
     * during writing. However, upon restart it will need to re-register those same classes before it can decode
     * them. This is apparently done automatically when calling database.getCollection(), but gets a little tricky
     * when decoding subclasses whose discriminators are not fully qualified class names with package. See Javadoc
     * on getAnalysisCollection() for how we register such subclasses.
     * We could register all these subclasses here via the PojoCodecProvider.Builder, but that separates their
     * registration from the place they're used. The builder has methods for registering whole packages, but these
     * methods do not auto-scan, they just provide the same behavior as automatic() but limited to specific packages.
     */
    private CodecRegistry makeCodecRegistry () {
        CodecProvider automaticPojoCodecProvider = PojoCodecProvider.builder().automatic(true).build();
        return CodecRegistries.fromRegistries(
                CodecRegistries.fromCodecs(new LocalDateCodec()),
                MongoClientSettings.getDefaultCodecRegistry(),
                CodecRegistries.fromProviders(automaticPojoCodecProvider)
        );
    }

    /**
     * Hit the codec registry with subclasses to cause it to build class models and codecs for them. This is necessary
     * when these subclasses specify short discriminators, as opposed to the verbose default discriminator of a fully
     * qualified class name, because the MongoDB driver does not auto-scan for classes it has not encountered in a write
     * operation or in a request for a collection.
     */
    private void addSubclassesToRegistry(Class<?> ...subclasses) {
        for (var subclass : subclasses) {
            database.getCodecRegistry().get(subclass);
        }
    }

    private <T extends BaseModel> AnalysisCollection<T> getAnalysisCollection(
            String name, Class<T> clazz
    ) {
        return new AnalysisCollection<>(database.getCollection(name, clazz));
    }

    /**
     * Interface to supply configuration to this component.
     */
    public interface Config {
        default String databaseUri() {
            return "mongodb://127.0.0.1:27017";
        }

        default String databaseName() {
            return "analysis";
        }
    }

}
