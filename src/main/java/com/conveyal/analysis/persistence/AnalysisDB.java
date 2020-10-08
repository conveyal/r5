package com.conveyal.analysis.persistence;

import com.mongodb.MongoClientSettings;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import org.bson.codecs.configuration.CodecRegistry;
import org.bson.codecs.pojo.PojoCodecProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

        // Create codec registry for POJOs
        CodecRegistry pojoCodecRegistry = fromRegistries(
                MongoClientSettings.getDefaultCodecRegistry(),
                fromProviders(PojoCodecProvider.builder().automatic(true).build()));

        database = mongo.getDatabase(config.databaseName()).withCodecRegistry(pojoCodecRegistry);

        // Reqeust that the JVM clean up database connections in all cases - exiting cleanly or by being terminated.
        // We should probably register such hooks for other components to shut down more cleanly.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            Persistence.mongo.close();
            this.mongo.close();
        }));
    }

    public AnalysisCollection getAnalysisCollection (String name, Class clazz) {
        return new AnalysisCollection<>(database.getCollection(name, clazz), clazz);
    }

    public MongoCollection getMongoCollection (String name, Class clazz) {
        return database.getCollection(name, clazz);
    }

    /** Interface to supply configuration to this component. */
    public interface Config {
        default String databaseUri() { return "mongodb://127.0.0.1:27017"; }
        default String databaseName() { return "analysis"; }
    }

}
