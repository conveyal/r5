package com.conveyal.analysis.persistence;

import com.conveyal.analysis.AnalysisServerException;
import com.conveyal.analysis.UserPermissions;
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

    public static final String MONGO_PROP_ACCESS_GROUP = "accessGroup";

    public final MongoCollection<T> collection;
    private final Class<T> type;

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

    public DeleteResult deleteByIdParamIfPermitted (Request request) {
        String _id = request.params("_id");
        UserPermissions user = UserPermissions.from(request);
        return collection.deleteOne(and(eq("_id", new ObjectId(_id)), eq("accessGroup", user.accessGroup)));
    }

    public List<T> findPermitted(Bson query, UserPermissions userPermissions) {
        return find(and(eq(MONGO_PROP_ACCESS_GROUP, userPermissions.accessGroup), query));
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

    public T findByIdIfPermitted (String _id, UserPermissions userPermissions) {
        T item = findById(_id);
        if (item.accessGroup.equals(userPermissions.accessGroup)) {
            return item;
        } else {
            // TODO: To simplify stack traces this should be refactored to "throw new InvalidAccessGroupException()"
            //       which should be a subtype of AnalysisServerException with methods like getHttpCode().
            throw invalidAccessGroup();
        }
    }

    public T create(T newModel, UserPermissions userPermissions) {
        newModel.accessGroup = userPermissions.accessGroup;
        newModel.createdBy = userPermissions.email;
        newModel.updatedBy = userPermissions.email;

        // This creates the `_id` automatically if it is missing
        collection.insertOne(newModel);

        return newModel;
    }

    /**
     * Note that if the supplied model has _id = null, the Mongo insertOne method will overwrite it with a new
     * ObjectId(). We consider it good practice to set the _id for any model object ourselves, avoiding this behavior.
     */
    public void insert (T model) {
        collection.insertOne(model);
    }

    public void insertMany (List<? extends T> models) {
        collection.insertMany(models);
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
                eq(MONGO_PROP_ACCESS_GROUP, accessGroup)
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

    // TODO should all below be static helpers on HttpController? Passing the whole request in seems to defy encapsulation.
    //  On the other hand, making them instance methods reduces the number of parameters and gives access to Class<T>.

    /**
     * Controller creation helper.
     */
    public T create(Request req, Response res) throws IOException {
        T value = JsonUtil.objectMapper.readValue(req.body(), type);
        return create(value, UserPermissions.from(req));
    }

    /**
     * Controller find by id helper.
     * TODO remove unused second parameter.
     */
    public T findPermittedByRequestParamId(Request req, Response res) {
        UserPermissions user = UserPermissions.from(req);
        T value = findById(req.params("_id"));
        // Throw if or does not have permission
        if (!value.accessGroup.equals(user.accessGroup)) {
            throw invalidAccessGroup();
        }
        return value;
    }

    /**
     * Controller update helper.
     */
    public T update(Request req, Response res) throws IOException {
        T value = JsonUtil.objectMapper.readValue(req.body(), type);
        final UserPermissions user = UserPermissions.from(req);
        value.updatedBy = user.email;
        if (!value.accessGroup.equals(user.accessGroup)) {
            throw invalidAccessGroup();
        }
        return update(value, user.accessGroup);
    }

}
