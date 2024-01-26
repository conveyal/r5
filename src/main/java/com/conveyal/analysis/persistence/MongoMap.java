package com.conveyal.analysis.persistence;

import com.conveyal.analysis.AnalysisServerException;
import com.conveyal.analysis.UserPermissions;
import com.conveyal.analysis.models.Model;
import com.conveyal.r5.common.JsonUtilities;
import com.mongodb.BasicDBObject;
import com.mongodb.DBObject;
import com.mongodb.QueryBuilder;
import org.bson.types.ObjectId;
import org.mongojack.DBCursor;
import org.mongojack.DBSort;
import org.mongojack.JacksonDBCollection;
import org.mongojack.WriteResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import spark.Request;

import java.io.IOException;
import java.util.Collection;

import static com.conveyal.analysis.persistence.AnalysisCollection.MONGO_PROP_ACCESS_GROUP;

/**
 * An attempt at simulating a MapDB-style interface, for storing Java objects in MongoDB.
 * Note this used to implement Map, but the Map interface predates generics in Java, so it is more typesafe not
 * to implement it.
 * TODO this is using org.mongojack.JacksonDBCollection. I believe Mongo Java client library now provides POJO storage.
 */
public class MongoMap<V extends Model> {

    private static Logger LOG = LoggerFactory.getLogger(MongoMap.class);

    private JacksonDBCollection<V, String> wrappedCollection;
    private Class<V> type;

    public MongoMap (JacksonDBCollection<V, String> wrappedCollection, Class<V> type) {
        this.type = type;
        this.wrappedCollection = wrappedCollection;
    }

    public int size() {
        return (int) wrappedCollection.getCount();
    }

    /**
     * `fields` is nullable.
     */
    public V findByIdIfPermitted(String id, DBObject fields, UserPermissions userPermissions) {
        V result = wrappedCollection.findOneById(id, fields);

        if (result == null) {
            throw AnalysisServerException.notFound(String.format(
                    "The resource you requested (_id %s) could not be found. Has it been deleted?", id
            ));
        } else if (!userPermissions.accessGroup.equals(result.accessGroup)) {
            throw AnalysisServerException.forbidden("You do not have permission to access this data.");
        } else {
            return result;
        }
    }

    public V findByIdIfPermitted(String id, UserPermissions userPermissions) {
        return findByIdIfPermitted(id, null, userPermissions);
    }

    public V findByIdFromRequestIfPermitted(Request request) {
        return findByIdIfPermitted(request.params("_id"), UserPermissions.from(request));
    }

    public V get(String key) {
        return wrappedCollection.findOneById(key);
    }

    public Collection<V> findAllForRequest(Request req) {
        return find(QueryBuilder.start(MONGO_PROP_ACCESS_GROUP).is(UserPermissions.from(req).accessGroup).get()).toArray();
    }

    /**
     * Helper function that adds the `accessGroup` to the query if the user is not an admin. If you want to query using
     * the `accessGroup` as an admin it must be added to the query.
     */
    public Collection<V> findPermitted(DBObject query, UserPermissions userPermissions) {
        DBCursor<V> cursor = find(QueryBuilder.start().and(
                query,
                QueryBuilder.start(MONGO_PROP_ACCESS_GROUP).is(userPermissions.accessGroup).get()
        ).get());

        return cursor.toArray();
    }

    // See comments for `findPermitted` above. This helper also adds a projection.
    public Collection<V> findPermitted(DBObject query, DBObject project, UserPermissions userPermissions) {
        DBCursor<V> cursor = find(QueryBuilder.start().and(
                query,
                QueryBuilder.start(MONGO_PROP_ACCESS_GROUP).is(userPermissions.accessGroup).get()
        ).get(), project);

        return cursor.toArray();
    }

    /**
     * Helper function that creates a query from the request query parameters. It will not filter by `accessGroup` as an
     * admin unless specifically added in the query.
     */
    public Collection<V> findPermittedForQuery (Request req) {
        QueryBuilder query = QueryBuilder.start();
        req.queryParams().forEach(name -> {
            query.and(name).is(req.queryParams(name));
        });
        return findPermitted(query.get(), UserPermissions.from(req));
    }

    /**
     * All Models have a createdAt field. By default, sort by that field.
     */
    public DBCursor<V> find(DBObject query) {
        return wrappedCollection.find(query).sort(DBSort.desc("createdAt"));
    }

    public DBCursor<V> find(DBObject query, DBObject project) {
        return wrappedCollection.find(query, project).sort(DBSort.desc("createdAt"));
    }

    /** Get all objects where property == value */
    public Collection<V> getByProperty (String property, Object value) {
        return wrappedCollection.find().is(property, value).toArray();
    }

    public V createFromJSONRequest(Request request) throws IOException {
        V json = JsonUtilities.objectMapper.readValue(request.body(), this.type);
        UserPermissions userPermissions = UserPermissions.from(request);
        json.accessGroup = userPermissions.accessGroup;
        json.createdBy = userPermissions.email;
        return create(json);
    }

    /**
     * Note that this has side effects on the object passed in! It assigns it an ID and creation/update time stamps.
     */
    public V create(V value) {
        // Create an ID
        value._id = new ObjectId().toString();

        // Set updated
        value.updateLock();

        // Set `createdAt` to `updatedAt` since it was first creation
        value.createdAt = value.updatedAt;

        // Set `updatedBy` to whomever created it
        value.updatedBy = value.createdBy;

        // Insert into the AnalysisDB
        wrappedCollection.insert(value);

        return value;
    }

    public V updateFromJSONRequest(Request request) throws IOException {
        V json = JsonUtilities.objectMapper.readValue(request.body(), this.type);
        // Add the additional check for the same access group
        return updateByUserIfPermitted(json, UserPermissions.from(request));
    }

    public V updateByUserIfPermitted(V value, UserPermissions userPermissions) {
        // Set `updatedBy`
        value.updatedBy = userPermissions.email;

        return put(value, QueryBuilder.start(MONGO_PROP_ACCESS_GROUP).is(userPermissions.accessGroup).get());
    }

    public V put(String key, V value) {
        if (!key.equals(value._id)) throw AnalysisServerException.badRequest("ID does not match");
        return put(value, null);
    }

    public V put(V value) {
        return put(value, null);
    }

    public V put(V value, DBObject optionalQuery) {
        String currentNonce = value.nonce;

        // Only update if the nonce is the same
        QueryBuilder query = QueryBuilder.start().and(
                QueryBuilder.start("_id").is(value._id).get(),
                QueryBuilder.start("nonce").is(currentNonce).get()
        );

        if (optionalQuery != null) query.and(optionalQuery);

        // Update the locking variables
        value.updateLock();

        // Set `createdAt` and `createdBy` if they have never been set
        if (value.createdAt == null) value.createdAt = value.updatedAt;
        if (value.createdBy == null) value.createdBy = value.updatedBy;

        // Convert the model into a db object
        BasicDBObject dbObject = JsonUtilities.objectMapper.convertValue(value, BasicDBObject.class);

        // Update
        V result = wrappedCollection.findAndModify(query.get(), null, null, false, dbObject, true, false);

        // If it doesn't result in an update, probably throw an error
        if (result == null) {
            result = wrappedCollection.findOneById(value._id);
            if (result == null) {
                throw AnalysisServerException.notFound("The data you attempted to update could not be found. ");
            } else if (!currentNonce.equals(result.nonce)) {
                throw AnalysisServerException.nonce();
            } else {
                throw AnalysisServerException.forbidden("The data you attempted to update is not in your access group.");
            }
        }
        LOG.debug("{} of {} updated {} ({})", result.updatedBy, result.accessGroup, result.name, result._id);

        // Return the object that was updated
        return result;
    }

    /**
     * Insert without updating the nonce or updateBy/updatedAt
     */
    public V modifiyWithoutUpdatingLock (V value) {
        wrappedCollection.updateById(value._id, value);

        return value;
    }

    public V removeIfPermitted(String key, UserPermissions userPermissions) {
        DBObject query = QueryBuilder.start().and(
                QueryBuilder.start("_id").is(key).get(),
                QueryBuilder.start(MONGO_PROP_ACCESS_GROUP).is(userPermissions.accessGroup).get()
        ).get();

        V result = wrappedCollection.findAndRemove(query);

        if (result == null) {
            throw AnalysisServerException.notFound("The data you attempted to remove could not be found.");
        }

        return result;
    }

    public V remove(String key) {
        WriteResult<V, String> result = wrappedCollection.removeById(key);
        LOG.info(result.toString());
        if (result.getN() == 0) {
            throw AnalysisServerException.notFound(String.format("The data for _id %s does not exist", key));
        }

        return null;
    }
}
