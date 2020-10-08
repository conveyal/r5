package com.conveyal.analysis.persistence;

import com.conveyal.analysis.AnalysisServerException;
import com.conveyal.analysis.models.BaseModel;
import com.conveyal.analysis.util.JsonUtil;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.result.DeleteResult;
import com.mongodb.client.result.UpdateResult;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;
import spark.Request;
import spark.Response;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;

public class AnalysisCollection<T extends BaseModel> {

    public MongoCollection<T> collection;
    private Class<T> type;

    private String getAccessGroup (Request req) {
        return req.attribute("accessGroup");
    }

    private AnalysisServerException invalidAccessGroup() {
        return AnalysisServerException.forbidden("Permission denied. Invalid access group.");
    }

    public AnalysisCollection(MongoCollection<T> collection, Class<T> clazz) {
        this.collection = collection;
        this.type = clazz;
    }

    public DeleteResult delete (T value) {
        return collection.deleteOne(eq("_id", value._id));
    }

    public List<T> findPermitted(Bson query, String accessGroup) {
        return find(and(eq("accessGroup", accessGroup), query));
    }

    public List<T> find(Bson query) {
        MongoCursor<T> cursor = collection.find(query).cursor();
        List<T> found = new ArrayList<>();
        while (cursor.hasNext()) {
            found.add(cursor.next());
        }
        return found;
    }

    public T findById(String _id) {
        return findById(new ObjectId(_id));
    }

    public T findById(ObjectId _id) {
        return collection.find(eq("_id", _id)).first();
    }

    public T create(T newModel, String accessGroup, String creatorEmail) {
        newModel.accessGroup = accessGroup;
        newModel.createdBy = creatorEmail;
        newModel.updatedBy = creatorEmail;

        // This creates the `_id` automatically
        collection.insertOne(newModel);

        return newModel;
    }

    public T update(T value) {
        return update(value, value.accessGroup);
    }

    public T update(T value, String accessGroup) {
        // Store the current nonce for querying and to check later if needed.
        ObjectId oldNonce = value.nonce;

        value.nonce = new ObjectId();

        UpdateResult result = collection.replaceOne(and(
                eq("_id", value._id),
                eq("nonce", oldNonce),
                eq("accessGroup", accessGroup)
        ), value);

        // If no documents were modified try to find the document to find out why
        if (result.getModifiedCount() != 1) {
            T model = findById(value._id);
            if (model == null) {
                throw AnalysisServerException.notFound(type.getName() + " was not found.");
            } else if (model.nonce != oldNonce) {
                throw AnalysisServerException.nonce();
            } else if (!model.accessGroup.equals(accessGroup)) {
                throw invalidAccessGroup();
            } else {
                throw AnalysisServerException.unknown("Unable to update model.");
            }
        }

        return value;
    }

    /**
     * Controller creation helper.
     */
    public T create(Request req, Response res) throws IOException {
        T value = JsonUtil.objectMapper.readValue(req.body(), type);

        String accessGroup = getAccessGroup(req);
        String email = req.attribute("email");
        return create(value, accessGroup, email);
    }

    /**
     * Controller find by id helper.
     */
    public T findPermittedByRequestParamId(Request req, Response res) {
        String accessGroup = getAccessGroup(req);
        T value = findById(req.params("_id"));

        // Throw if or does not have permission
        if (!value.accessGroup.equals(accessGroup)) {
            throw invalidAccessGroup();
        }

        return value;
    }

    /**
     * Controller update helper.
     */
    public T update(Request req, Response res) throws IOException {
        T value = JsonUtil.objectMapper.readValue(req.body(), type);

        String accessGroup = getAccessGroup(req);
        value.updatedBy = req.attribute("email");

        if (!value.accessGroup.equals(accessGroup)) throw invalidAccessGroup();

        return update(value, accessGroup);
    }
}
