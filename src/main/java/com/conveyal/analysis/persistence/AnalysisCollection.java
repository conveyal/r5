package com.conveyal.analysis.persistence;

import com.conveyal.analysis.AnalysisServerException;
import com.conveyal.analysis.UserPermissions;
import com.conveyal.analysis.models.BaseModel;
import com.mongodb.client.FindIterable;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.result.DeleteResult;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;
import spark.Request;

import java.util.ArrayList;
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

    public DeleteResult deleteByIdIfPermitted(String _id, UserPermissions user) {
        return collection.deleteOne(and(eq("_id", _id), eq(MONGO_PROP_ACCESS_GROUP, user.accessGroup)));
    }

    public FindIterable<T> findPermitted(Bson query, UserPermissions userPermissions) {
        return collection.find(and(eq(MONGO_PROP_ACCESS_GROUP, userPermissions.accessGroup), query));
    }

    public FindIterable<T> findById(String _id) {
        return collection.find(eq("_id", _id));
    }

    public T findByIdIfPermitted(String _id, UserPermissions userPermissions) {
        T item = findById(_id).first();
        if (item == null) throw AnalysisServerException.notFound(_id + " not found");
        if (item.accessGroup.equals(userPermissions.accessGroup)) {
            return item;
        } else {
            // TODO: To simplify stack traces this should be refactored to "throw new InvalidAccessGroupException()"
            //       which should be a subtype of AnalysisServerException with methods like getHttpCode().
            throw invalidAccessGroup();
        }
    }

    /**
     * Note that if the supplied model has _id = null, the Mongo insertOne method will overwrite it with a new
     * ObjectId(). We consider it good practice to set the _id for any model object ourselves, avoiding this behavior.
     */
    public void insert(T model) {
        if (model._id == null) model._id = new ObjectId().toString();
        collection.insertOne(model);
    }

    public void replaceOne(T value) {
        this.collection.replaceOne(eq("_id", value._id), value);
    }

    /*
      Helpers for HttpControllers
     */

    /**
     * Delete a document by the _id path parameter in the request, checking permissions.
     *
     * @param request
     * @return
     */
    public DeleteResult deleteByIdParamIfPermitted(Request request) {
        var _id = request.params("_id");
        var user = UserPermissions.from(request);
        return deleteByIdIfPermitted(_id, user);
    }

    /**
     * Find a document by the _id path parameter in the request, checking permissions.
     */
    public T findPermittedByRequestParamId(Request req) {
        return findByIdIfPermitted(req.params("_id"), UserPermissions.from(req));
    }

}
