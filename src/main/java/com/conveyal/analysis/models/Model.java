package com.conveyal.analysis.models;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.bson.types.ObjectId;

import javax.persistence.Id;
import java.util.Date;

import static com.conveyal.analysis.util.JsonUtil.objectMapper;
import static com.conveyal.file.UrlWithHumanName.filenameCleanString;
import static com.google.common.base.Preconditions.checkArgument;

/**
 * Shared superclass for data model classes that are serialized to communicate between the UI and the backend,
 * and generally stored in the application MongoDB.
 * Other objects that are serialized and sent to the workers are not subclasses of this.
 */
public abstract class Model implements Cloneable {

    @Id
    public String _id;

    public String name;

    public String nonce;

    public Date createdAt;
    public Date updatedAt;

    public void updateLock() {
        this.nonce = new ObjectId().toString();
        this.updatedAt = new Date();
    }

    public String accessGroup;
    public String createdBy;
    public String updatedBy;

    /**
     * Since this is the common superclass for objects that are sent over an HTTP API as JSON and stored in MongoDB
     * (where objects are also commonly represented as JSON) our default human-readable representation is as JSON.
     * However, we avoid relying on this behavior to produce machine-readable output, preferring to explicitly
     * specify conversion to JSON rather than relying on implicit toString behavior.
     */
    @Override
    public String toString () {
        try {
            return objectMapper.writeValueAsString(this);
        } catch (JsonProcessingException e) {
            e.printStackTrace();
            return super.toString();
        }
    }

    /**
     * Given an Entity, make a human-readable name for the entity composed of its user-supplied name as well as
     * the most rapidly changing digits of its ID to disambiguate in case multiple entities have the same name.
     * It is also possible to find the exact entity in many web UI fields using this suffix of its ID.
     */
    public String humanName () {
        // Most or all IDs encountered are MongoDB ObjectIDs. The first four and middle five bytes are slow-changing
        // and would not disambiguate between data sets. Only the 3-byte counter at the end will be sure to change.
        // See https://www.mongodb.com/docs/manual/reference/method/ObjectId/
        checkArgument(_id.length() > 6, "ID had too few characters.");
        String shortId = _id.substring(_id.length() - 6, _id.length());
        String humanName = "%s_%s".formatted(filenameCleanString(name), shortId);
        return humanName;
    }
}
