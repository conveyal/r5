package com.conveyal.analysis.persistence;

import com.conveyal.analysis.AnalysisServerException;
import com.conveyal.analysis.UserPermissions;
import com.conveyal.analysis.models.BaseModel;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.result.DeleteResult;
import org.bson.conversions.Bson;
import spark.Request;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Filters.eq;

public class AnalysisCollection<T extends BaseModel> {

    public static final String MONGO_PROP_ACCESS_GROUP = "accessGroup";

    public final MongoCollection<T> collection;

    private AnalysisServerException invalidAccessGroup() {
        return AnalysisServerException.forbidden("Permission denied. Invalid access group.");
    }

    public AnalysisCollection(MongoCollection<T> collection) {
        this.collection = collection;
    }

    public List<T> toArray(MongoCursor<T> cursor) {
        var list = new ArrayList<T>();
        while (cursor.hasNext()) {
            list.add(cursor.next());
        }
        return list;
    }

    public DeleteResult delete(T value) {
        return collection.deleteOne(eq("_id", value._id));
    }

    public DeleteResult deleteByIdParamIfPermitted(Request request) {
        String _id = request.params("_id");
        UserPermissions user = UserPermissions.from(request);
        return deleteByIdIfPermitted(_id, user);
    }

    public DeleteResult deleteByIdIfPermitted(String _id, UserPermissions user) {
        return collection.deleteOne(and(eq("_id", _id), eq(MONGO_PROP_ACCESS_GROUP, user.accessGroup)));
    }

    public FindIterable<T> findPermitted(Bson query, UserPermissions userPermissions) {
        return find(and(eq(MONGO_PROP_ACCESS_GROUP, userPermissions.accessGroup), query));
    }

    public FindIterable<T> find(Bson query) {
        return collection.find(query);
    }

    public T findById(String _id) {
        return collection.find(eq("_id", _id)).first();
    }

    public T findByIdIfPermitted(String _id, UserPermissions userPermissions) {
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
        newModel.createdAt = new Date();
        newModel.updatedAt = new Date();

        // This creates the `_id` automatically if it is missing
        collection.insertOne(newModel);

        return newModel;
    }

    /**
     * Note that if the supplied model has _id = null, the Mongo insertOne method will overwrite it with a new
     * ObjectId(). We consider it good practice to set the _id for any model object ourselves, avoiding this behavior.
     * It looks like we could remove the OBJECT_ID_GENERATORS convention to force explicit ID creation.
     * https://mongodb.github.io/mongo-java-driver/3.11/bson/pojos/#conventions
     */
    public void insert(T model) {
        collection.insertOne(model);
    }

    public T modifyWithoutUpdatingLock(T value) {
        this.collection.replaceOne(eq("_id", value._id), value);
        return value;
    }

    // TODO should all below be static helpers on HttpController? Passing the whole request in seems to defy encapsulation.
    //  On the other hand, making them instance methods reduces the number of parameters and gives access to Class<T>.

    /**
     * Helper for HttpControllers - find a document by the _id path parameter in the request, checking permissions.
     */
    public T findPermittedByRequestParamId(Request req) {
        return findByIdIfPermitted(req.params("_id"), UserPermissions.from(req));
    }
}
