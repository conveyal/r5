package com.conveyal.analysis.controllers;

import com.conveyal.analysis.UserPermissions;
import com.conveyal.analysis.persistence.AnalysisDB;
import com.google.common.collect.Lists;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import spark.Request;
import spark.Response;

import java.io.OutputStream;
import java.lang.invoke.MethodHandles;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.conveyal.analysis.util.JsonUtil.toJson;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;

/**
 * Serve up arbitrary records from the database without binding to Java objects.
 * This converts BSON to JSON. Similar things could be done converting relational rows to JSON.
 * This allows authenticated retrieval of anything in the database by the UI, even across schema migrations.
 */
public class DatabaseController implements HttpController {

    private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    private final AnalysisDB database;

    private final Map<String, MongoCollection<Document>> mongoCollections;

    // Preloading these avoids synchronization during handling http requests by reading from an immutable map.
    // TODO verify if it is threadsafe to reuse MongoCollection in all threads.
    // Amazingly there seems to be no documentation on this at all. Drilling down into the function calls, it seems
    // to create a new session on each find() call, so should presumably go through synchronization.
    // In testing with siege and other http benchmarking tools, reusing the MongoCollection seems to result in much
    // smoother operation; creating a new MongoCollection on each request seems to jam up after a certain number
    // of requests (perhaps waiting for idle MongoCollectons to be cleaned up).
    public Map<String, MongoCollection<Document>> mongoCollectionMap (String... collectionNames) {
        Map<String, MongoCollection<Document>> map = new HashMap<>();
        for (String name : collectionNames) {
            map.put(name, database.getBsonCollection(name));
        }
        // Make the map immutable for threadsafe reading and return.
        return Map.copyOf(map);
    }

    public DatabaseController(AnalysisDB database) {
        this.database = database;
        this.mongoCollections = mongoCollectionMap("regions", "bundles");
    }

    /** Factored out for experimenting with streaming and non-streaming approaches to serialization. */
    private FindIterable<Document> getDocuments (Request req) {
        String accessGroup = UserPermissions.from(req).accessGroup;
        final String collectionName = req.params("collection");
        MongoCollection<Document> collection = mongoCollections.get(collectionName);
        checkNotNull(collection, "Collection not available: " + collectionName);
        List<Bson> filters = Lists.newArrayList(eq("accessGroup", accessGroup));
        req.queryMap().toMap().forEach((key, values) -> {
            for (String value : values) {
                filters.add(eq(key, value));
            }
        });
        return collection.find(and(filters));
    }

    /**
     * Fetch anything from database. Buffers all documents in memory so may not not suitable for large responses.
     * Register result serialization with: sparkService.get("/api/db/:collection", this::getDocuments, toJson);
     */
    private Iterable<Document> getDocuments (Request req, Response res) {
        FindIterable<Document> docs = getDocuments(req);
        List<Document> documents = new ArrayList<>();
        docs.into(documents);
        return documents;
    }

    /**
     * Fetch anything from database. Streaming processing, no in-memory buffering of the BsonDocuments.
     * The output stream does buffer to some extent but should stream chunks instead of serializing into memory.
     * Anecdotally in testing with seige this does seem to almost double the response rate and allow double the
     * concurrent connections without stalling (though still low at 20, and it eventually does stall).
     */
    private Object getDocumentsStreaming (Request req, Response res) {
        FindIterable<Document> docs = getDocuments(req);
        // getOutputStream returns a ServletOutputStream, usually Jetty implementation HttpOutputStream which
        // buffers the output. doc.toJson() creates a lot of short-lived objects which could be factored out.
        // The Mongo driver says to use JsonWriter or toJson() rather than utility methods:
        // https://github.com/mongodb/mongo-java-driver/commit/63409f9cb3bbd0779dd5139355113d9b227dfa05
        try {
            OutputStream out = res.raw().getOutputStream();
            out.write('['); // Begin JSON array.
            boolean firstElement = true;
            for (Document doc : docs) {
                if (firstElement) {
                    firstElement = false;
                } else {
                    out.write(',');
                }
                out.write(doc.toJson().getBytes(StandardCharsets.UTF_8));
            }
            out.write(']'); // Close JSON array.
            // We do not close the OutputStream, even implicitly with a try-with-resources.
            // The thinking is that closing the stream might close the underlying connection, which might be keepalive.
        } catch (Exception e) {
            throw new RuntimeException("Failed to write database records as JSON.", e);
        }
        // Since we're directly writing to the OutputStream, no need to return anything.
        // But do not return null or Spark will complain cryptically.
        return "";
    }

    @Override
    public void registerEndpoints (spark.Service sparkService) {
        sparkService.get("/api/db/:collection", this::getDocuments, toJson);
        //sparkService.get("/api/db/:collection", this::getDocumentsStreaming);
    }

}
