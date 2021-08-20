package com.conveyal.analysis.persistence;

import com.conveyal.analysis.models.BaseModel;
import com.conveyal.analysis.models.SpatialDataSource;
import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import org.bson.codecs.configuration.CodecProvider;
import org.bson.codecs.configuration.CodecRegistry;
import org.bson.codecs.pojo.Conventions;
import org.bson.codecs.pojo.PojoCodecProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static com.mongodb.MongoClientSettings.getDefaultCodecRegistry;
import static org.bson.codecs.configuration.CodecRegistries.fromProviders;
import static org.bson.codecs.configuration.CodecRegistries.fromRegistries;

public class AnalysisDB {

    private final Logger LOG = LoggerFactory.getLogger(AnalysisDB.class);
    private final MongoClient mongo;
    private final MongoDatabase database;

    public AnalysisDB (Config config) {
        if (config.databaseUri() != null) {
            LOG.info("Connecting to remote MongoDB instance...");
            mongo = MongoClients.create(config.databaseUri());
        } else {
            LOG.info("Connecting to local MongoDB instance...");
            mongo = MongoClients.create();
        }
        database = mongo.getDatabase(config.databaseName()).withCodecRegistry(makeCodecRegistry());

        // Request that the JVM clean up database connections in all cases - exiting cleanly or by being terminated.
        // We should probably register such hooks for other components to shut down more cleanly.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            Persistence.mongo.close();
            this.mongo.close();
        }));
    }

    /**
     * Create a codec registry that has all the default codecs (dates, geojson, etc.) and falls back to a provider
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
        CodecRegistry pojoCodecRegistry = fromRegistries(
                getDefaultCodecRegistry(),
                fromProviders(automaticPojoCodecProvider)
        );
        return pojoCodecRegistry;
    }

    /**
     * If the optional subclasses are supplied, the codec registry will be hit to cause it to build class models and
     * codecs for them. This is necessary when these subclasses specify short discriminators, as opposed to the
     * verbose default discriminator of a fully qualified class name, because the Mongo driver does not auto-scan for
     * classes it has not encountered in a write operation or in a request for a collection.
     */
    public <T extends BaseModel> AnalysisCollection getAnalysisCollection (
        String name, Class<T> clazz, Class<? extends T>... subclasses
    ){
        for (Class subclass : subclasses) {
            database.getCodecRegistry().get(subclass);
        }
        return new AnalysisCollection<T>(database.getCollection(name, clazz), clazz);
    }

    /** Interface to supply configuration to this component. */
    public interface Config {
        default String databaseUri() { return "mongodb://127.0.0.1:27017"; }
        default String databaseName() { return "analysis"; }
    }

}
