package com.conveyal.analysis.controllers;

import com.conveyal.analysis.UserPermissions;
import com.conveyal.analysis.persistence.AnalysisDB;
import com.google.common.collect.Lists;
import com.mongodb.client.MongoCollection;
import com.mongodb.util.JSON;
import org.bson.BsonArray;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.bson.json.JsonWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import spark.Request;
import spark.Response;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.Writer;
import java.lang.invoke.MethodHandles;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static com.conveyal.analysis.util.JsonUtil.toJson;
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

    private final MongoCollection<Document> regions;
    private final MongoCollection<Document> bundles;

    public DatabaseController(AnalysisDB database) {
        this.database = database;
        // TODO verify if it is threadsafe to reuse this collection in all threads
        // Also verify whether it's any slower to just get the collection on every GET operation.
        // Testing with Apache bench, retaining and reusing the collection seems much smoother.
        this.regions = database.getBsonCollection("regions");
        this.bundles = database.getBsonCollection("bundles");
    }

    /**
     * Fetch anything from database. Buffers in memory so not suitable for huge responses.
     * register serialization with sparkService.get("/api/db/:collection", this::getDocuments, toJson);
     */
    private Iterable<Document> getDocuments (Request req, Response res) {
        String accessGroup = UserPermissions.from(req).accessGroup;
        final String collectionName = req.params("collection");
        MongoCollection<Document> collection = collectionName.equals("bundles") ? bundles :
                database.getBsonCollection(collectionName);
        List<Bson> filters = Lists.newArrayList(eq("accessGroup", accessGroup));
        req.queryMap().toMap().forEach((key, values) -> {
            for (String value : values) {
                filters.add(eq(key, value));
            }
        });
        List<Document> documents = new ArrayList<>();
        collection.find(and(filters)).into(documents);
        return documents;
    }

    /**
     * Fetch anything from database. Streaming processing, no in-memory buffering of the BsonDocuments.
     * The output stream does buffer to some extent but should stream chunks instead of serializing into memory.
     */
    private Object getDocumentsStreaming (Request req, Response res) {
        String accessGroup = UserPermissions.from(req).accessGroup;
        final String collectionName = req.params("collection");
        MongoCollection<Document> collection = collectionName.equals("bundles") ? bundles :
                database.getBsonCollection(collectionName);
        List<Bson> filters = Lists.newArrayList(eq("accessGroup", accessGroup));
        req.queryMap().toMap().forEach((key, values) -> {
            for (String value : values) {
                filters.add(eq(key, value));
            }
        });
        // getOutputStream returns a ServletOutputStream, usually Jetty implementation HttpOutputStream which
        // buffers the output. doc.toJson() creates a lot of short-lived objects which could be factored out.
        // The Mongo driver says to use JsonWriter or toJson() rather than utility methods:
        // https://github.com/mongodb/mongo-java-driver/commit/63409f9cb3bbd0779dd5139355113d9b227dfa05
        try (OutputStream out = res.raw().getOutputStream()) {
            out.write('['); // Begin JSON array.
            boolean firstElement = true;
            for (Document doc : collection.find(and(filters))) {
                if (firstElement) {
                    firstElement = false;
                } else {
                    out.write(',');
                }
                out.write(doc.toJson().getBytes(StandardCharsets.UTF_8));
            }
            out.write(']'); // Close JSON array.
        } catch (IOException e) {
            throw new RuntimeException("Failed to write database records as JSON.", e);
        }
        // Since we're directly writing to the OutputStream, no need to return anything.
        // But do not return null or Spark will complain cryptically.
        return "";
    }

    // Testing with Apache bench shows some stalling
    // -k keepalive connections fails immediately

    @Override
    public void registerEndpoints (spark.Service sparkService) {
        sparkService.get("/api/db/:collection", this::getDocuments, toJson);
        //sparkService.get("/api/db/:collection", this::getDocumentsStreaming);
    }

}
